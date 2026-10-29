package net.onelitefeather.blackhole.backend.analytics;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * PostgreSQL implementation. The lock is a session-level advisory lock on a connection of its own, so it is held for the whole run
 * and released with the connection: a crashed replica cannot keep it.
 */
@Singleton
public class JdbcRefreshSessions implements RefreshSessions {

    /** Fixed key of the refresh lock, shared by every replica of the backend. */
    static final long LOCK_KEY = 4_786_231_902_417_001L;

    private final DataSource dataSource;

    @Inject
    public JdbcRefreshSessions(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcRefreshSessions(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<RefreshSession> tryStart() {
        Connection connection = open();
        try {
            if (!tryLock(connection)) {
                connection.close();
                return Optional.empty();
            }
            return Optional.of(new JdbcRefreshSession(connection));
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("could not take the analytics refresh lock", e);
        }
    }

    private Connection open() {
        try {
            return this.dataSource.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("could not open a connection for the analytics refresh", e);
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The connection is abandoned; the server drops its session locks with it.
        }
    }

    private static final class JdbcRefreshSession implements RefreshSession {

        private final Connection connection;

        JdbcRefreshSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public boolean isPopulated(String view) {
            try (PreparedStatement statement = this.connection.prepareStatement(
                    "SELECT ispopulated FROM pg_matviews WHERE schemaname = 'analytics' AND matviewname = ?")) {
                statement.setString(1, view);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() && rows.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not read the state of analytics view " + view, e);
            }
        }

        @Override
        public void refresh(String view, boolean concurrently) {
            // The view name comes from AnalyticsViews.ALL, never from input.
            String sql = "REFRESH MATERIALIZED VIEW " + (concurrently ? "CONCURRENTLY " : "") + "analytics." + view;
            try (Statement statement = this.connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("could not refresh analytics view " + view, e);
            }
        }

        @Override
        public void recordRefreshed(String view, long refreshedAtMillis) {
            String sql = """
                    INSERT INTO analytics.refresh_state (view_name, refreshed_at) VALUES (?, ?)
                    ON CONFLICT (view_name) DO UPDATE SET refreshed_at = EXCLUDED.refreshed_at
                    """;
            try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
                statement.setString(1, view);
                statement.setLong(2, refreshedAtMillis);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("could not record the refresh of analytics view " + view, e);
            }
        }

        @Override
        public void close() {
            try (PreparedStatement statement = this.connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            } catch (SQLException ignored) {
                // Closing the connection below releases the session lock in any case.
            } finally {
                closeQuietly(this.connection);
            }
        }
    }
}
