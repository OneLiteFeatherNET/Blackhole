package net.onelitefeather.blackhole.backend.integration;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** PostgreSQL implementation of {@link IntegrationDetectionStore}, over {@code integration_detection} (V44). */
@Singleton
public class JdbcIntegrationDetectionStore implements IntegrationDetectionStore {

    /** The idempotency key is (integration_id, detection_id): a repeat inserts nothing and reports zero rows. */
    private static final String INSERT = "INSERT INTO integration_detection (integration_id, detection_id, kind, schema_version, "
            + "owner, server, occurred_at, received_at, severity, summary, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) "
            + "ON CONFLICT (integration_id, detection_id) DO NOTHING";
    /** Attached rows (attached_evidence_id set) follow the evidence lifetime and are never swept here. */
    private static final String DELETE_UNATTACHED = "DELETE FROM integration_detection WHERE attached_evidence_id IS NULL AND occurred_at < ?";

    private final DataSource dataSource;

    public JdbcIntegrationDetectionStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public boolean insertIfAbsent(NewDetection detection) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setLong(1, detection.integrationId());
            statement.setObject(2, detection.detectionId());
            statement.setString(3, detection.kind());
            statement.setShort(4, (short) detection.schemaVersion());
            statement.setString(5, detection.ownerHash());
            statement.setString(6, detection.server());
            statement.setLong(7, detection.occurredAt());
            statement.setLong(8, detection.receivedAt());
            if (detection.severity() == null) {
                statement.setNull(9, Types.VARCHAR);
            } else {
                statement.setString(9, detection.severity());
            }
            statement.setString(10, detection.summary());
            statement.setString(11, detection.payloadJson());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("integration detection could not be written", e);
        }
    }

    @Override
    public int deleteUnattachedOccurredBefore(long cutoff) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_UNATTACHED)) {
            statement.setLong(1, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("integration detections could not be swept", e);
        }
    }
}
