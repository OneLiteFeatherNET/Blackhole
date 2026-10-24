package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChangeNotifier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Moves the player's seen marker of a case in one statement. The marker only grows: {@code GREATEST} keeps the larger value, and the
 * {@code WHERE} clause makes a stale read a no-op, so nothing is reported to staff unless the marker actually moved.
 */
@Singleton
public class JdbcPlayerSeenStore implements PlayerSeenMarker {

    private static final String MOVE = """
            UPDATE punishment_case SET player_seen_seq = GREATEST(player_seen_seq, ?)
            WHERE punishment_identifier = ? AND player_seen_seq < ?
            """;

    private final DataSource dataSource;
    private final CaseChangeNotifier notifier;

    @Inject
    public JdbcPlayerSeenStore(DataSource dataSource, DataSourceResolver resolver, CaseChangeNotifier notifier) {
        this.dataSource = resolver.resolve(dataSource);
        this.notifier = notifier;
    }

    /** For a plain data source and a notifier, such as a test's. */
    public JdbcPlayerSeenStore(DataSource plainDataSource, CaseChangeNotifier notifier) {
        this.dataSource = plainDataSource;
        this.notifier = notifier;
    }

    @Override
    public boolean moveTo(String punishmentIdentifier, long seq) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(MOVE)) {
            statement.setLong(1, seq);
            statement.setString(2, punishmentIdentifier);
            statement.setLong(3, seq);
            boolean moved = statement.executeUpdate() == 1;
            if (moved) {
                this.notifier.seenMoved(punishmentIdentifier, seq);
            }
            return moved;
        } catch (SQLException e) {
            throw new IllegalStateException("could not move the seen marker of " + punishmentIdentifier, e);
        }
    }
}
