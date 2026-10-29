package net.onelitefeather.blackhole.backend.publicbans;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The generated column {@code player_hash_v1} of V32 is the one implementation of the player hash scheme. Runs the whole
 * migration chain on a fresh PostgreSQL and reads the column the way the list does.
 */
@Tag("integration")
class PublicBanPlayerHashIntegrationTest extends PostgresIntegrationTest {

    private static final UUID VECTOR = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String VECTOR_HASH = "9efc1073aa30a316b4d4930dbf73bf852be666299041e930c9763de9f0c03029";

    @BeforeEach
    void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @Test
    void theColumnOfTheTestVectorIsTheDocumentedDigest() throws SQLException {
        String identifier = insertPunishment(VECTOR.toString());

        assertEquals(VECTOR_HASH, playerHashOf(identifier), "the database derives the documented digest from the stored UUID");
    }

    @Test
    void anUppercaseSpellingOfTheSameUuidGivesTheSameHash() throws SQLException {
        String identifier = insertPunishment(VECTOR.toString().toUpperCase());

        assertEquals(VECTOR_HASH, playerHashOf(identifier), "the uuid type normalises the spelling before the hash is taken");
    }

    @Test
    void aPunishmentWithoutAUuidHasNoPlayerHash() throws SQLException {
        String identifier = insertPunishment(null);

        assertNull(playerHashOf(identifier), "no UUID, no player hash");
    }

    private String insertPunishment(String playerUuid) throws SQLException {
        String identifier = "pb-" + UUID.randomUUID();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO punishments (identifier, short_id, owner, section_key, permanent, lifted, kind, player_uuid) "
                             + "VALUES (?, ?, ?, ?, false, false, 'PUNISHMENT', CAST(? AS uuid))")) {
            statement.setString(1, identifier);
            statement.setString(2, UUID.randomUUID().toString().substring(0, 8));
            statement.setString(3, "ab".repeat(64));
            statement.setString(4, "chat");
            statement.setString(5, playerUuid);
            statement.executeUpdate();
        }
        return identifier;
    }

    private String playerHashOf(String identifier) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT player_hash_v1 FROM punishments WHERE identifier = ?")) {
            statement.setString(1, identifier);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
