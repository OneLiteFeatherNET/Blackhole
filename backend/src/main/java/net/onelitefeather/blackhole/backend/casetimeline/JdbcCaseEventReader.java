package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PostgreSQL implementation of {@link CaseEventReader}. */
@Singleton
public class JdbcCaseEventReader implements CaseEventReader {

    private static final String TIMELINE = """
            SELECT identifier, seq, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                   actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload::text, redacted_at, dedupe_key
            FROM case_event WHERE punishment_identifier = ? ORDER BY occurred_at, seq
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcCaseEventReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcCaseEventReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public List<CaseEventRecord> timeline(String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TIMELINE)) {
            statement.setString(1, punishmentIdentifier);
            try (ResultSet rows = statement.executeQuery()) {
                List<CaseEventRecord> events = new ArrayList<>();
                while (rows.next()) {
                    Long redactedAt = rows.getObject(15, Long.class);
                    events.add(new CaseEventRecord(
                            rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getInt(5),
                            rows.getLong(6), CaseEventOrigin.valueOf(rows.getString(7)), ActorTrust.valueOf(rows.getString(8)),
                            rows.getObject(9, UUID.class), rows.getObject(10, UUID.class), rows.getObject(11, UUID.class),
                            rows.getString(12), rows.getString(13), CaseEventPayloads.parse(rows.getString(14)),
                            redactedAt, rows.getString(16)));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the case timeline of " + punishmentIdentifier, e);
        }
    }
}
