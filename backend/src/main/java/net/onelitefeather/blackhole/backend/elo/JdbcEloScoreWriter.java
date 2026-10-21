package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL implementation of {@link EloScoreWriter}: single-statement upserts in auto-commit mode.
 */
@Singleton
public class JdbcEloScoreWriter implements EloScoreWriter {

    private static final String ADD_DELTA = """
            INSERT INTO elo_scores (owner, section_key, score, updated_at) VALUES (?, ?, ? + ?, ?)
            ON CONFLICT (owner, section_key)
            DO UPDATE SET score = elo_scores.score + ?, updated_at = ?
            RETURNING score
            """;
    private static final String SET_SCORE = """
            INSERT INTO elo_scores (owner, section_key, score, updated_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (owner, section_key)
            DO UPDATE SET score = EXCLUDED.score, updated_at = EXCLUDED.updated_at
            """;
    private static final String RECOVER = """
            UPDATE elo_scores SET score = score + ?, updated_at = ?
            WHERE owner = ? AND section_key = ? AND updated_at = ?
            """;

    private final DataSource dataSource;

    public JdbcEloScoreWriter(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public int addDelta(String owner, String sectionKey, int baseScore, int delta, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ADD_DELTA)) {
            statement.setString(1, owner);
            statement.setString(2, sectionKey);
            statement.setInt(3, baseScore);
            statement.setInt(4, delta);
            statement.setLong(5, now);
            statement.setInt(6, delta);
            statement.setLong(7, now);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not add a score delta", e);
        }
    }

    @Override
    public void setScore(String owner, String sectionKey, int score, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SET_SCORE)) {
            statement.setString(1, owner);
            statement.setString(2, sectionKey);
            statement.setInt(3, score);
            statement.setLong(4, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not set a score", e);
        }
    }

    @Override
    public boolean recover(String owner, String sectionKey, int recovery, long expectedUpdatedAt, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RECOVER)) {
            statement.setInt(1, recovery);
            statement.setLong(2, now);
            statement.setString(3, owner);
            statement.setString(4, sectionKey);
            statement.setLong(5, expectedUpdatedAt);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not apply score recovery", e);
        }
    }
}
