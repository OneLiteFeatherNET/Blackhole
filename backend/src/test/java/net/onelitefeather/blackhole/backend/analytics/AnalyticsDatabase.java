package net.onelitefeather.blackhole.backend.analytics;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A database of its own on the test container, migrated by the real Flyway migrations. The analytics views and the reporting role are
 * per database or cluster-wide, so every test starts from a database that no other test touches.
 */
final class AnalyticsDatabase {

    private final String url;
    private final String user;
    private final String password;
    private final String name;

    private AnalyticsDatabase(String url, String user, String password, String name) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.name = name;
    }

    /** Creates a fresh database next to the one in {@code adminJdbcUrl} and applies every migration to it. */
    static AnalyticsDatabase create(String adminJdbcUrl, String user, String password) {
        String name = "analytics_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = adminJdbcUrl.substring(0, adminJdbcUrl.lastIndexOf('/') + 1);
        AnalyticsDatabase database = new AnalyticsDatabase(prefix + name, user, password, name);
        try (Connection admin = DriverManager.getConnection(adminJdbcUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the test database " + name, e);
        }
        Flyway.configure()
                .dataSource(database.url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return database;
    }

    /** Drops the database; a test calls it in {@code @AfterEach}. */
    void drop(String adminJdbcUrl) {
        try (Connection admin = DriverManager.getConnection(adminJdbcUrl, this.user, this.password);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + this.name + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("could not drop the test database " + this.name, e);
        }
    }

    /** A new connection to this database, for a test that needs its own session (a role, or a held lock). The caller closes it. */
    Connection open() throws SQLException {
        return DriverManager.getConnection(this.url, this.user, this.password);
    }

    DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(this.url);
        source.setUser(this.user);
        source.setPassword(this.password);
        return source;
    }

    String url() {
        return this.url;
    }

    void execute(String sql, Object... parameters) {
        try (Connection connection = DriverManager.getConnection(this.url, this.user, this.password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    /** The first column of every row. */
    List<Object> column(String sql, Object... parameters) {
        List<Object> values = new ArrayList<>();
        for (List<Object> row : rows(sql, parameters)) {
            values.add(row.getFirst());
        }
        return values;
    }

    /** Every row, every column, in order. */
    List<List<Object>> rows(String sql, Object... parameters) {
        try (Connection connection = DriverManager.getConnection(this.url, this.user, this.password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<List<Object>> rows = new ArrayList<>();
                int width = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    List<Object> row = new ArrayList<>(width);
                    for (int i = 1; i <= width; i++) {
                        row.add(resultSet.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not query " + sql, e);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
