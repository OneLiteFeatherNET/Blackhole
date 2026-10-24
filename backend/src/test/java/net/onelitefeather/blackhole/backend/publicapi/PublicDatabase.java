package net.onelitefeather.blackhole.backend.publicapi;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A schema of its own in the test container, migrated by the real Flyway migrations, so every test
 * starts from a fresh database and shares nothing with the others.
 */
final class PublicDatabase {

    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    final DataSource dataSource;
    private final String url;
    private final String user;
    private final String password;
    private final String schema = "public_test_" + SCHEMAS.incrementAndGet();

    PublicDatabase(String jdbcUrl, String user, String password) {
        this.url = jdbcUrl;
        this.user = user;
        this.password = password;
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + this.schema);
        source.setUser(user);
        source.setPassword(password);
        this.dataSource = source;
    }

    /** Applies the migrations up to and including {@code target} ({@code "latest"} for all of them). */
    PublicDatabase migrate(String target) {
        Flyway.configure()
                .dataSource(this.url, this.user, this.password)
                .schemas(this.schema)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
        return this;
    }

    void execute(String sql, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    /** Runs a script of several statements, such as the backfill of a migration. */
    void script(String sql) {
        try (Connection connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not run the script", e);
        }
    }

    /** The first column of every row. */
    List<Object> column(String sql, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<Object> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getObject(1));
                }
                return values;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    long count(String sql, Object... parameters) {
        return ((Number) column(sql, parameters).getFirst()).longValue();
    }

    /** The first column of every row, as text. */
    List<String> rows(String sql, Object... parameters) {
        return column(sql, parameters).stream().map(String::valueOf).toList();
    }

    String text(String sql, Object... parameters) {
        List<Object> values = column(sql, parameters);
        return values.isEmpty() ? null : (String) values.getFirst();
    }
}
