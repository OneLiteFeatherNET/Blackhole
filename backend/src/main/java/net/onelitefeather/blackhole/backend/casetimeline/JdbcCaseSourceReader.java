package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.AppealRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.EvidenceRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.KickRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link CaseSourceReader}. Reads only; the numbers in the JSON
 * metadata are read defensively (a value that is not a number is treated as absent).
 */
@Singleton
public class JdbcCaseSourceReader implements CaseSourceReader {

    private static final String UUID_PATTERN = "'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'";

    /** A punishment with the values of its JSON metadata the case events need; wrapped by the queries below. */
    private static final String PUNISHMENTS = """
            SELECT * FROM (
                SELECT p.identifier, p.kind, p.source, p.recorded_by_oid, p.actor_oid, p.section_key, p.step_index, p.step_count,
                       p.elo_cost, p.permanent, p.lifted, COALESCE(p.created_at, 0) AS created_at,
                       CASE WHEN jsonb_typeof(p.meta_data -> 'expiration_date') = 'number'
                            THEN (p.meta_data ->> 'expiration_date')::numeric::bigint END AS expires_at,
                       CASE WHEN jsonb_typeof(p.meta_data -> 'update_date') = 'number'
                            THEN (p.meta_data ->> 'update_date')::numeric::bigint END AS updated_at,
                       p.template_identifier,
                       CASE WHEN p.meta_data ->> 'revokedBy' ~* %1$s THEN (p.meta_data ->> 'revokedBy')::uuid END AS ended_by,
                       CASE WHEN p.meta_data ->> 'recordedByOid' ~* %1$s THEN (p.meta_data ->> 'recordedByOid')::uuid END AS ended_recorded_by,
                       (SELECT a.identifier FROM appeals a WHERE a.punishment_identifier = p.identifier AND a.status = %2$d
                         ORDER BY a.updated_at DESC LIMIT 1) AS lifting_appeal
                FROM punishments p
            ) x
            """.formatted(UUID_PATTERN, AppealStatus.GRANTED_FULL_LIFT.ordinal());

    private static final String NO_EVENT = "NOT EXISTS (SELECT 1 FROM case_event ce WHERE ce.dedupe_key = %s)";

    // A kick is recorded as punishment.kicked, never as applied, ended or expired.
    private static final String APPLIED_MISSING = PUNISHMENTS + " WHERE x.kind = 'PUNISHMENT' AND x.created_at >= ? AND "
            + NO_EVENT.formatted("'applied:' || x.identifier") + " ORDER BY x.created_at, x.identifier LIMIT ?";
    private static final String ENDED_MISSING = PUNISHMENTS + " WHERE x.kind = 'PUNISHMENT' AND x.lifted AND COALESCE(x.updated_at, x.created_at) >= ? AND "
            + NO_EVENT.formatted("'revoked:' || x.identifier") + " AND " + NO_EVENT.formatted("'lifted:' || x.identifier")
            + " ORDER BY COALESCE(x.updated_at, x.created_at), x.identifier LIMIT ?";
    private static final String EXPIRED_MISSING = PUNISHMENTS + " WHERE x.kind = 'PUNISHMENT' AND NOT x.lifted AND x.expires_at >= ? AND x.expires_at < ? AND "
            + NO_EVENT.formatted("'expired:' || x.identifier") + " ORDER BY x.expires_at, x.identifier LIMIT ?";

    private static final String KICKED_MISSING = """
            SELECT p.identifier, p.source, p.recorded_by_oid, p.actor_oid, p.kick_scope, p.kick_target, p.elo_cost,
                   COALESCE(p.created_at, 0) AS created_at
            FROM punishments p
            WHERE p.kind = 'KICK' AND COALESCE(p.created_at, 0) >= ? AND
            """ + NO_EVENT.formatted("'kicked:' || p.identifier") + " ORDER BY p.created_at, p.identifier LIMIT ?";

    private static final String APPEALS = """
            SELECT a.identifier, a.punishment_identifier, a.status, a.decided_by, a.recorded_by_oid, a.created_at, a.updated_at
            FROM appeals a
            """;
    private static final String FILED_MISSING = APPEALS + " WHERE a.punishment_identifier IS NOT NULL AND a.created_at >= ? AND "
            + NO_EVENT.formatted("'appeal.filed:' || a.identifier") + " ORDER BY a.created_at, a.identifier LIMIT ?";
    private static final String DECIDED_MISSING = APPEALS + " WHERE a.punishment_identifier IS NOT NULL AND a.status IN (%d, %d, %d) AND a.updated_at >= ? AND "
            .formatted(AppealStatus.GRANTED_FULL_LIFT.ordinal(), AppealStatus.GRANTED_DURATION_REDUCTION.ordinal(), AppealStatus.DENIED.ordinal())
            + NO_EVENT.formatted("'appeal.decided:' || a.identifier") + " ORDER BY a.updated_at, a.identifier LIMIT ?";

    private static final String EVIDENCE = """
            SELECT ei.identifier, ei.punishment_identifier, ei.kind, ei.source_system, ei.created_by, ei.recorded_by_oid,
                   ei.actor_oid, ei.created_at,
                   (SELECT min(au.occurred_at) FROM evidence_audit au WHERE au.evidence_identifier = ei.identifier AND au.action = 0) AS attached_at,
                   ei.status, ei.status_changed_at, ei.lifted_by_appeal
            FROM evidence_items ei
            """;
    // A stored protocol (TRANSCRIPT) is recorded as protocol.stored, never as evidence.attached.
    private static final String ATTACHED_MISSING = EVIDENCE + " WHERE ei.punishment_identifier IS NOT NULL AND ei.kind <> "
            + EvidenceKind.TRANSCRIPT.ordinal() + " AND ei.created_at >= ? AND "
            + NO_EVENT.formatted("'evidence.attached:' || ei.identifier") + " ORDER BY ei.created_at, ei.identifier LIMIT ?";
    private static final String STATUS_MISSING = EVIDENCE + " WHERE ei.punishment_identifier IS NOT NULL AND ei.status IN (%d, %d) AND "
            .formatted(EvidenceStatus.REVOKED.ordinal(), EvidenceStatus.LIFTED.ordinal())
            + "COALESCE(ei.status_changed_at, ei.created_at) >= ? AND "
            + NO_EVENT.formatted("(CASE ei.status WHEN %d THEN 'evidence.revoked:' ELSE 'evidence.lifted:' END) || ei.identifier"
            .formatted(EvidenceStatus.REVOKED.ordinal()))
            + " ORDER BY COALESCE(ei.status_changed_at, ei.created_at), ei.identifier LIMIT ?";

    private final DataSource dataSource;

    @Inject
    public JdbcCaseSourceReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcCaseSourceReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public List<PunishmentRow> appliedMissing(long since, int limit) {
        return query(APPLIED_MISSING, JdbcCaseSourceReader::punishment, since, limit);
    }

    @Override
    public List<KickRow> kickedMissing(long since, int limit) {
        return query(KICKED_MISSING, JdbcCaseSourceReader::kick, since, limit);
    }

    @Override
    public List<PunishmentRow> endedMissing(long since, int limit) {
        return query(ENDED_MISSING, JdbcCaseSourceReader::punishment, since, limit);
    }

    @Override
    public List<PunishmentRow> expiredMissing(long since, long now, int limit) {
        return query(EXPIRED_MISSING, JdbcCaseSourceReader::punishment, since, now, limit);
    }

    @Override
    public List<AppealRow> filedMissing(long since, int limit) {
        return query(FILED_MISSING, JdbcCaseSourceReader::appeal, since, limit);
    }

    @Override
    public List<AppealRow> decidedMissing(long since, int limit) {
        return query(DECIDED_MISSING, JdbcCaseSourceReader::appeal, since, limit);
    }

    @Override
    public List<EvidenceRow> attachedMissing(long since, int limit) {
        return query(ATTACHED_MISSING, JdbcCaseSourceReader::evidence, since, limit);
    }

    @Override
    public List<EvidenceRow> statusChangeMissing(long since, int limit) {
        return query(STATUS_MISSING, JdbcCaseSourceReader::evidence, since, limit);
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<T> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(mapper.map(rows));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not look for lost case events", e);
        }
    }

    private static PunishmentRow punishment(ResultSet rows) throws SQLException {
        return new PunishmentRow(
                rows.getString("identifier"), rows.getObject("source", UUID.class), rows.getObject("recorded_by_oid", UUID.class),
                rows.getObject("actor_oid", UUID.class), rows.getString("section_key"), rows.getObject("step_index", Integer.class),
                rows.getObject("step_count", Integer.class), rows.getObject("elo_cost", Integer.class), rows.getBoolean("permanent"),
                rows.getBoolean("lifted"), rows.getLong("created_at"), rows.getObject("expires_at", Long.class),
                rows.getObject("updated_at", Long.class), rows.getObject("template_identifier", UUID.class),
                rows.getObject("ended_by", UUID.class), rows.getObject("ended_recorded_by", UUID.class),
                rows.getObject("lifting_appeal", UUID.class));
    }

    private static KickRow kick(ResultSet rows) throws SQLException {
        return new KickRow(
                rows.getString("identifier"), rows.getObject("source", UUID.class), rows.getObject("recorded_by_oid", UUID.class),
                rows.getObject("actor_oid", UUID.class), rows.getString("kick_scope"), rows.getString("kick_target"),
                rows.getObject("elo_cost", Integer.class), rows.getLong("created_at"));
    }

    private static AppealRow appeal(ResultSet rows) throws SQLException {
        return new AppealRow(
                rows.getObject("identifier", UUID.class), rows.getString("punishment_identifier"),
                AppealStatus.values()[rows.getInt("status")], rows.getObject("decided_by", UUID.class),
                rows.getObject("recorded_by_oid", UUID.class), rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    private static EvidenceRow evidence(ResultSet rows) throws SQLException {
        return new EvidenceRow(
                rows.getObject("identifier", UUID.class), rows.getString("punishment_identifier"),
                EvidenceKind.values()[rows.getInt("kind")], rows.getString("source_system"), rows.getObject("created_by", UUID.class),
                rows.getObject("recorded_by_oid", UUID.class), rows.getObject("actor_oid", UUID.class), rows.getLong("created_at"),
                rows.getObject("attached_at", Long.class), EvidenceStatus.values()[rows.getInt("status")],
                rows.getObject("status_changed_at", Long.class), rows.getObject("lifted_by_appeal", UUID.class));
    }
}
