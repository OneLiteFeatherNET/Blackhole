package net.onelitefeather.blackhole.backend.rollback;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fresh, fully migrated PostgreSQL schema per test, plus the few raw statements the rollback tests
 * need to set up punishment rows and to read back what a preview wrote.
 */
final class RollbackTestSchema {

    static final UUID STAFF = UUID.nameUUIDFromBytes("staff-alice".getBytes());
    static final UUID OTHER_STAFF = UUID.nameUUIDFromBytes("staff-bob".getBytes());
    static final UUID SERVICE = UUID.nameUUIDFromBytes("proxy-service".getBytes());
    static final UUID PROXY_OID = UUID.nameUUIDFromBytes("proxy-token".getBytes());
    static final UUID STAFF_REQUESTER = UUID.nameUUIDFromBytes("admin-token".getBytes());
    static final UUID MINECRAFT_UNLINKED = UUID.nameUUIDFromBytes("minecraft-unlinked".getBytes());

    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    private RollbackTestSchema() {
    }

    /** Migrates a new schema with the real migrations and returns a data source pointed at it. */
    static PGSimpleDataSource fresh(String jdbcUrl, String user, String password) {
        String schema = "rollback_test_" + SCHEMAS.incrementAndGet();
        Flyway.configure().dataSource(jdbcUrl, user, password).schemas(schema).locations("classpath:db/migration").load().migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        run(dataSource, "INSERT INTO staff_actor (oid, kind, display_name, first_seen_at, last_seen_at) VALUES (?, 'HUMAN', 'Alice', 1, 1)", STAFF);
        run(dataSource, "INSERT INTO staff_actor (oid, kind, display_name, first_seen_at, last_seen_at) VALUES (?, 'HUMAN', 'Bob', 1, 1)", OTHER_STAFF);
        run(dataSource, "INSERT INTO staff_actor (oid, kind, display_name, first_seen_at, last_seen_at) VALUES (?, 'SERVICE', 'proxy', 1, 1)", SERVICE);
        // The sections 'network' and 'chat' are seeded by V2, so the fixture uses them as they are.
        return dataSource;
    }

    static String owner(int n) {
        return String.format("%02x", n).repeat(64);
    }

    /**
     * A punishment row. {@code recordedBy} and {@code actor} are the attribution columns; {@code active}
     * puts it into the owner's section slot so that it can be revoked. Creates the profile row first.
     */
    static void givenPunishment(PGSimpleDataSource dataSource, String id, int owner, String section, UUID source, UUID recordedBy,
                                UUID actor, long createdAt, boolean lifted, boolean active) {
        run(dataSource, "INSERT INTO punishment_profiles (owner, meta_data) VALUES (?, '{}'::jsonb) ON CONFLICT DO NOTHING", owner(owner));
        run(dataSource, "INSERT INTO punishments (identifier, source, recorded_by_oid, actor_oid, owner, section_key, permanent, lifted, created_at, "
                + "short_id, meta_data) VALUES (?, ?, ?, ?, ?, ?, false, ?, ?, 'ABCDEF01', '{\"reason\":\"because\"}'::jsonb)",
                id, source, recordedBy, actor, owner(owner), section, lifted, createdAt);
        if (active) {
            run(dataSource, "INSERT INTO active_punishments (owner, section_key, punishment_identifier) VALUES (?, ?, ?)", owner(owner), section, id);
        }
    }

    static void run(PGSimpleDataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    static long count(PGSimpleDataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count " + sql, e);
        }
    }
}
