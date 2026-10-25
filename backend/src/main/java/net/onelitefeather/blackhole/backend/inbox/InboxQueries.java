package net.onelitefeather.blackhole.backend.inbox;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.report.ReportStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link InboxSource}. One query per section and one count per section, both
 * on short connections. Enum columns are ordinals (appeals, reports) or names (cases), so the status
 * values are bound from the enums.
 */
@Singleton
public class InboxQueries implements InboxSource {

    /**
     * The last visible message of each appeal chat, found through the index on (punishment, seq). A deleted
     * message is not visible, so it is skipped; the chat needs an answer when that last message is the player's.
     */
    private static final String NEEDS_ANSWER_FROM = """
            FROM appeals a
            JOIN LATERAL (
                SELECT e.occurred_at, e.payload -> 'author' ->> 'kind' AS kind
                FROM case_event e
                WHERE e.punishment_identifier = a.punishment_identifier
                  AND e.type = 'message.added' AND e.visibility = 'PLAYER'
                  AND e.payload ->> 'appealId' = a.identifier::text
                  AND NOT EXISTS (SELECT 1 FROM case_event d WHERE d.type = 'message.deleted' AND d.subject_id = e.subject_id)
                ORDER BY e.seq DESC
                LIMIT 1
            ) lm ON lm.kind = 'PLAYER'
            LEFT JOIN punishments p ON p.identifier = a.punishment_identifier
            WHERE a.status IN (?, ?, ?)
            """;
    private static final String NEEDS_ANSWER_SELECT =
            "SELECT a.identifier::text, p.short_id, COALESCE(p.owner, a.appellant_hash), lm.occurred_at ";
    private static final String NEEDS_ANSWER_ORDER = "lm.occurred_at, a.identifier";

    private static final String OPEN_APPEALS_FROM = """
            FROM appeals a
            LEFT JOIN punishments p ON p.identifier = a.punishment_identifier
            WHERE a.status IN (?, ?, ?)
            """;
    private static final String OPEN_APPEALS_SELECT =
            "SELECT a.identifier::text, p.short_id, COALESCE(p.owner, a.appellant_hash), a.created_at ";
    private static final String OPEN_APPEALS_ORDER = "a.created_at, a.identifier";

    private static final String NEW_REPORTS_FROM = "FROM reports r WHERE r.status = ?";
    private static final String NEW_REPORTS_SELECT = "SELECT r.identifier::text, NULL, r.reported_hash, r.created_at ";
    private static final String NEW_REPORTS_ORDER = "r.created_at, r.identifier";

    private static final String UNASSIGNED_CASES_FROM = """
            FROM punishment_case c
            JOIN punishments p ON p.identifier = c.punishment_identifier
            WHERE c.status = ? AND c.assignee_oid IS NULL
            """;
    private static final String UNASSIGNED_CASES_SELECT =
            "SELECT c.punishment_identifier, p.short_id, p.owner, c.created_at ";
    private static final String UNASSIGNED_CASES_ORDER = "c.created_at, c.punishment_identifier";

    private final DataSource dataSource;

    @Inject
    public InboxQueries(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public InboxQueries(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public List<InboxDTO.Item> oldest(InboxSection section, int limit) {
        String sql = select(section) + from(section) + " ORDER BY " + order(section) + " LIMIT ?";
        List<Object> parameters = new ArrayList<>(parameters(section));
        parameters.add(limit);
        List<InboxDTO.Item> items = new ArrayList<>();
        query(sql, parameters, result -> items.add(new InboxDTO.Item(
                result.getString(1), result.getString(2), result.getString(3), result.getLong(4))));
        return items;
    }

    @Override
    public long count(InboxSection section) {
        long[] total = {0};
        query("SELECT count(*) " + from(section), parameters(section), result -> total[0] = result.getLong(1));
        return total[0];
    }

    private static String select(InboxSection section) {
        return switch (section) {
            case NEEDS_ANSWER -> NEEDS_ANSWER_SELECT;
            case OPEN_APPEALS -> OPEN_APPEALS_SELECT;
            case NEW_REPORTS -> NEW_REPORTS_SELECT;
            case UNASSIGNED_CASES -> UNASSIGNED_CASES_SELECT;
        };
    }

    private static String from(InboxSection section) {
        return switch (section) {
            case NEEDS_ANSWER -> NEEDS_ANSWER_FROM;
            case OPEN_APPEALS -> OPEN_APPEALS_FROM;
            case NEW_REPORTS -> NEW_REPORTS_FROM;
            case UNASSIGNED_CASES -> UNASSIGNED_CASES_FROM;
        };
    }

    private static String order(InboxSection section) {
        return switch (section) {
            case NEEDS_ANSWER -> NEEDS_ANSWER_ORDER;
            case OPEN_APPEALS -> OPEN_APPEALS_ORDER;
            case NEW_REPORTS -> NEW_REPORTS_ORDER;
            case UNASSIGNED_CASES -> UNASSIGNED_CASES_ORDER;
        };
    }

    private static List<Object> parameters(InboxSection section) {
        return switch (section) {
            case NEEDS_ANSWER, OPEN_APPEALS -> List.of(
                    (short) AppealStatus.SUBMITTED.ordinal(),
                    (short) AppealStatus.ELIGIBLE_PENDING_REVIEW.ordinal(),
                    (short) AppealStatus.IN_REVIEW.ordinal());
            case NEW_REPORTS -> List.of((short) ReportStatus.OPEN.ordinal());
            case UNASSIGNED_CASES -> List.of(CaseStatus.OPEN.name());
        };
    }

    private void query(String sql, List<Object> parameters, RowReader reader) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reader.read(result);
                }
            }
        } catch (SQLException e) {
            throw new InboxUnavailableException("could not read the inbox", e);
        }
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet result) throws SQLException;
    }
}
