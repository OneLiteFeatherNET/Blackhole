package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V25 turns the raw csrf value of a session that is already stored into its SHA-256 digest. */
@Tag("integration")
class PlayerSessionCsrfMigrationIntegrationTest extends PostgresIntegrationTest {

    @Test
    void migrationV25ReplacesAStoredRawCsrfValueWithItsSha256DigestAndKeepsTheSession() throws NoSuchAlgorithmException {
        PublicDatabase db = new PublicDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("24");
        db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES ('p-1', ?, ?, 'network', false, false, 1, 'AAAAAAAA', '{}'::jsonb)", UUID.nameUUIDFromBytes("staff".getBytes()), "ab".repeat(64));
        db.execute("INSERT INTO player_session (token_hash, punishment_identifier, scope, csrf, created_at, last_seen_at, expires_at) "
                + "VALUES ('h-1', 'p-1', 'FULL', 'raw-csrf-value', 1, 1, 9999999999999)");

        db.migrate("latest");

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("raw-csrf-value".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, db.text("SELECT csrf FROM player_session WHERE token_hash = 'h-1'"), "the same digest the service computes");
    }
}
