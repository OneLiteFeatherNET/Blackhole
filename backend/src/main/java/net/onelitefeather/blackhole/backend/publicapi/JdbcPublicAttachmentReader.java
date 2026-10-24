package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link PublicAttachmentReader}. Two statements, both filtered by the punishment: a message file needs a
 * player-visible, not removed message that carries it (the payload names the attachment id), a shared image needs an active share.
 */
@Singleton
public class JdbcPublicAttachmentReader implements PublicAttachmentReader {

    private static final String MESSAGE_FILE = """
            SELECT e.identifier, e.file_key, e.file_name, e.file_content_type, e.file_size
            FROM evidence_items e
            WHERE e.identifier = ? AND e.punishment_identifier = ? AND e.kind = ? AND e.file_key IS NOT NULL
              AND EXISTS (
                SELECT 1 FROM case_event m
                WHERE m.punishment_identifier = e.punishment_identifier AND m.type = 'message.added' AND m.visibility = 'PLAYER'
                  AND m.redacted_at IS NULL
                  AND m.payload -> 'attachments' @> jsonb_build_array(jsonb_build_object('attachmentId', e.identifier::text))
                  AND NOT EXISTS (SELECT 1 FROM case_event d
                                  WHERE d.punishment_identifier = m.punishment_identifier AND d.subject_id = m.subject_id
                                    AND d.type IN ('message.deleted', 'message.redacted'))
              )
            """;

    private static final String SHARED_IMAGE = """
            SELECT e.identifier, e.file_key, e.file_name, e.file_content_type, e.file_size
            FROM evidence_share s JOIN evidence_items e ON e.identifier = s.evidence_id
            WHERE s.share_id = ? AND s.punishment_identifier = ? AND s.revoked_at IS NULL AND s.mode = 'FILE'
              AND e.file_key IS NOT NULL
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcPublicAttachmentReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcPublicAttachmentReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<Visible> find(String punishmentIdentifier, UUID id) {
        Optional<Visible> file = one(MESSAGE_FILE, statement -> {
            statement.setObject(1, id);
            statement.setString(2, punishmentIdentifier);
            statement.setShort(3, (short) EvidenceKind.FILE.ordinal());
        });
        if (file.isPresent()) {
            return file;
        }
        return one(SHARED_IMAGE, statement -> {
            statement.setObject(1, id);
            statement.setString(2, punishmentIdentifier);
        });
    }

    private Optional<Visible> one(String sql, Binder binder) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Visible(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getLong(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read a chat file", e);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
