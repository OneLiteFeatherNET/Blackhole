package net.onelitefeather.blackhole.backend.banid;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs V13 over punishments that exist at V12, then issues their Ban IDs with the backfill. */
@Tag("integration")
class BanIdMigrationIntegrationTest extends PostgresIntegrationTest {

    private static Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setCurrentSchema(schema);
        return dataSource;
    }

    private static void insertPunishment(Connection connection, String identifier) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO punishments (identifier, short_id) VALUES (?, ?)")) {
            insert.setString(1, identifier);
            insert.setString(2, PunishmentShortId.storageKey(identifier));
            insert.executeUpdate();
        }
    }

    private static void insertAppeal(Connection connection, String punishment, int status) throws SQLException {
        insertAppeal(connection, punishment, status, 1);
    }

    private static UUID insertAppeal(Connection connection, String punishment, int status, long createdAt) throws SQLException {
        UUID identifier = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO appeals (identifier, created_at, updated_at, status, punishment_identifier) VALUES (?, ?, ?, ?, ?)")) {
            insert.setObject(1, identifier);
            insert.setLong(2, createdAt);
            insert.setLong(3, createdAt);
            insert.setInt(4, status);
            insert.setString(5, punishment);
            insert.executeUpdate();
        }
        return identifier;
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void existingPunishmentsKeepNoBanIdUntilTheBackfillIssuesOneAndASecondRunChangesNothing() throws SQLException {
        Random random = new Random(42);
        List<String> identifiers = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            identifiers.add(IdGenerator.generateId(new UUID(random.nextLong(), random.nextLong())));
        }
        flyway("ban_id_backfill", "12").migrate();
        PGSimpleDataSource dataSource = dataSource("ban_id_backfill");
        try (Connection connection = dataSource.getConnection()) {
            for (String identifier : identifiers) {
                insertPunishment(connection, identifier);
            }
            insertAppeal(connection, identifiers.get(0), 5);
            insertAppeal(connection, identifiers.get(0), 5);
            insertAppeal(connection, identifiers.get(0), 2);

            flyway("ban_id_backfill", "13").migrate();

            assertEquals(250, count(connection, "SELECT count(*) FROM punishments WHERE ban_id_hash IS NULL AND ban_id_enc IS NULL AND ban_id_gen = 1"),
                    "existing rows are untouched by the migration");

            BanIdCrypto crypto = new BanIdCrypto(new BanIdProperties(TestKeys.randomBase64()));
            JdbcBanIdStore store = new JdbcBanIdStore(dataSource);
            BanIdService service = new BanIdService(store, crypto, new BanIdGenerator(new Random(1)), null);
            BanIdBackfill backfill = new BanIdBackfill(store, service, new SimpleMeterRegistry());

            assertEquals(250, backfill.run());
            assertEquals(0, store.countWithoutBanId());
            assertEquals(250, count(connection, "SELECT count(DISTINCT ban_id_hash) FROM punishments"), "all unique");
            byte[] before = null;
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT ban_id_hash FROM punishments WHERE identifier = '" + identifiers.get(7) + "'")) {
                rows.next();
                before = rows.getBytes(1);
            }

            assertEquals(0, backfill.run(), "idempotent");

            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT ban_id_hash, ban_id_enc FROM punishments WHERE identifier = '" + identifiers.get(7) + "'")) {
                rows.next();
                assertTrue(java.util.Arrays.equals(before, rows.getBytes(1)), "the Ban ID did not change");
                String banId = crypto.decrypt(rows.getBytes(2), identifiers.get(7));
                assertNotNull(banId, "decrypts with the punishment as associated data");
                assertEquals(java.util.Optional.of(identifiers.get(7)), store.identifierByHash(crypto.hash(banId)));
            }
            Set<String> texts = new HashSet<>();
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT ban_id_enc FROM punishments LIMIT 5")) {
                while (rows.next()) {
                    texts.add(new String(rows.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
            assertEquals(5, texts.size(), "a fresh nonce per row");
        }
    }

    @Test
    void aSecondOpenAppealForOnePunishmentIsRefusedAfterTheMigration() throws SQLException {
        flyway("ban_id_open_appeal", "13").migrate();
        try (Connection connection = dataSource("ban_id_open_appeal").getConnection()) {
            String identifier = IdGenerator.generateId(UUID.randomUUID());
            insertPunishment(connection, identifier);
            insertAppeal(connection, identifier, 0);
            insertAppeal(connection, identifier, 5);

            assertThrows(SQLException.class, () -> insertAppeal(connection, identifier, 2), "a second open appeal");
        }
    }

    @Test
    void existingDuplicateOpenAppealsDoNotFailTheMigrationAndOnlyTheNewestStaysUnderTheRule() throws SQLException {
        flyway("ban_id_duplicates", "12").migrate();
        PGSimpleDataSource dataSource = dataSource("ban_id_duplicates");
        try (Connection connection = dataSource.getConnection()) {
            String crowded = IdGenerator.generateId(UUID.randomUUID());
            String single = IdGenerator.generateId(UUID.randomUUID());
            insertPunishment(connection, crowded);
            insertPunishment(connection, single);
            UUID oldest = insertAppeal(connection, crowded, 0, 10);
            UUID middle = insertAppeal(connection, crowded, 2, 20);
            UUID newest = insertAppeal(connection, crowded, 1, 30);
            UUID decided = insertAppeal(connection, crowded, 5, 40);
            UUID alone = insertAppeal(connection, single, 0, 10);

            flyway("ban_id_duplicates", "13").migrate();

            assertEquals(java.util.Set.of(oldest, middle), flagged(connection), "the older open appeals are flagged, nothing else");
            assertEquals(5, count(connection, "SELECT count(*) FROM appeals"), "no appeal is removed");
            assertEquals(java.util.List.of(0, 2, 1, 5, 0), statuses(connection, oldest, middle, newest, decided, alone), "no status changes");
            assertThrows(SQLException.class, () -> insertAppeal(connection, crowded, 0, 50), "the rule holds for the newest one");
        }
    }

    private static Set<UUID> flagged(Connection connection) throws SQLException {
        Set<UUID> flagged = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT identifier FROM appeals WHERE legacy_duplicate_open")) {
            while (rows.next()) {
                flagged.add(rows.getObject(1, UUID.class));
            }
        }
        return flagged;
    }

    private static List<Integer> statuses(Connection connection, UUID... identifiers) throws SQLException {
        List<Integer> statuses = new ArrayList<>();
        for (UUID identifier : identifiers) {
            try (PreparedStatement select = connection.prepareStatement("SELECT status FROM appeals WHERE identifier = ?")) {
                select.setObject(1, identifier);
                try (ResultSet rows = select.executeQuery()) {
                    rows.next();
                    statuses.add(rows.getInt(1));
                }
            }
        }
        return statuses;
    }
}
