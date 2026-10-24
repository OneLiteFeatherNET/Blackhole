package net.onelitefeather.blackhole.backend.live;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL implementation of {@link LiveSequence}: the highest seq the audience may see. A player sees what the player read sees
 * (the same visibility and type allow-list); staff see every event of the case.
 */
@Singleton
public class JdbcLiveSequence implements LiveSequence {

    private static final String STAFF = "SELECT COALESCE(max(seq), 0) FROM case_event WHERE punishment_identifier = ?";
    private static final String PLAYER = "SELECT COALESCE(max(seq), 0) FROM case_event WHERE punishment_identifier = ? "
            + "AND ((visibility = 'PLAYER' AND type = ANY (?)) OR type = ANY (?))";

    private final DataSource dataSource;

    @Inject
    public JdbcLiveSequence(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcLiveSequence(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public long current(String caseKey, LiveAudience audience) {
        try (Connection connection = this.dataSource.getConnection()) {
            String sql = audience == LiveAudience.PLAYER ? PLAYER : STAFF;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, caseKey);
                if (audience == LiveAudience.PLAYER) {
                    Array visible = connection.createArrayOf("text", CaseEventPayload.PORTAL_VISIBLE_TYPES.toArray());
                    Array anyVisibility = connection.createArrayOf("text", CaseEventPayload.PORTAL_ANY_VISIBILITY_TYPES.toArray());
                    statement.setArray(2, visible);
                    statement.setArray(3, anyVisibility);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the current seq of a case", e);
        }
    }
}
