package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client fingerprint against real PostgreSQL: the upsert, the link in the sightings, retention, erasure and the key gate. */
@Tag("integration")
class ClientFingerprintApiIntegrationTest extends ApiIntegrationTest {

    private static final String ME = "f1".repeat(64);
    private static final String ALT = "f2".repeat(64);
    private static final String THIRD = "f3".repeat(64);

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.clientrules.fingerprint-key", TestKeys.randomBase64(), "blackhole.evasion.ip-salt", "test-salt");
    }

    private Reply submit(String owner, String channel) {
        return post("/evasion/fingerprint", Map.of("owner", owner, "brand", "zq-secret-brand", "channels", List.of(channel, "minecraft:brand"), "mods", List.of()),
                this.jwt.proxy());
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoOwnersWithTheSameClientAreLinkedViaClientWithoutAnyTraitInTheResponseOrTheDatabase() throws SQLException {
        String channel = "zq-secret-channel:" + System.nanoTime();
        assertEquals(200, submit(ME, channel).status());
        assertEquals(200, submit(ALT, channel).status());
        assertEquals(200, submit(ALT, channel).status(), "a repeat is an update, not a second row");

        Reply reply = get("/evasion/" + ME + "/sightings", this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertFalse(reply.body().contains("zq-secret"), "no trait in the response");
        List<Map<String, Object>> linked = (List<Map<String, Object>>) reply.json().get("linkedOwners");
        assertEquals(1, linked.size(), reply.body());
        assertEquals(ALT, linked.getFirst().get("owner"));
        assertEquals("CLIENT", linked.getFirst().get("via"));
        assertEquals(true, reply.json().get("clientFingerprintEnabled"));
        assertEquals(0, count("SELECT count(*) FROM client_fingerprint WHERE client_fingerprint::text LIKE '%zq-secret%'"));
        assertEquals(2, count("SELECT count(*) FROM client_fingerprint WHERE owner_hash IN ('" + ME + "', '" + ALT + "')"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOwnerOnTheSameIpAndTheSameClientIsLinkedViaBoth() {
        String channel = "zq-both:" + System.nanoTime();
        post("/evasion/record", Map.of("owner", "f4".repeat(64), "ip", "203.0.113.99"));
        post("/evasion/record", Map.of("owner", "f5".repeat(64), "ip", "203.0.113.99"));
        submit("f4".repeat(64), channel);
        submit("f5".repeat(64), channel);

        Reply reply = get("/evasion/" + "f4".repeat(64) + "/sightings", this.jwt.viewer());

        List<Map<String, Object>> linked = (List<Map<String, Object>>) reply.json().get("linkedOwners");
        assertEquals("BOTH", linked.getFirst().get("via"), reply.body());
    }

    @Test
    void genericTraitsAreAcceptedButNotStored() throws SQLException {
        Reply reply = post("/evasion/fingerprint", Map.of("owner", THIRD, "brand", "vanilla", "channels", List.of("minecraft:register"), "mods", List.of()),
                this.jwt.proxy());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(false, reply.json().get("stored"));
        assertEquals(0, count("SELECT count(*) FROM client_fingerprint WHERE owner_hash = '" + THIRD + "'"));
    }

    @Test
    void aMalformedOwnerAndAnOversizedListAreRejected() {
        assertEquals(400, post("/evasion/fingerprint", Map.of("owner", "nope", "brand", "x"), this.jwt.proxy()).status());
        List<String> tooMany = java.util.stream.IntStream.range(0, 257).mapToObj(i -> "c:" + i).toList();
        assertEquals(400, post("/evasion/fingerprint", Map.of("owner", ME, "brand", "x", "channels", tooMany), this.jwt.proxy()).status());
    }

    @Test
    void onlyTheProxyMaySubmit() {
        Map<String, Object> body = Map.of("owner", ME, "brand", "x", "channels", List.of("a:b"));

        assertEquals(403, post("/evasion/fingerprint", body, this.jwt.admin()).status());
        assertEquals(403, post("/evasion/fingerprint", body, this.jwt.moderator()).status());
        assertEquals(403, post("/evasion/fingerprint", body, this.jwt.viewer()).status());
        assertEquals(401, post("/evasion/fingerprint", body, null).status());
    }

    @Test
    void expiredFingerprintsAreSwept() throws SQLException {
        String owner = "f6".repeat(64);
        submit(owner, "zq-sweep:" + System.nanoTime());
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE client_fingerprint SET last_seen = 1 WHERE owner_hash = '" + owner + "'");
        }

        this.context.getBean(ClientFingerprintRetentionSweeper.class).sweep();

        assertEquals(0, count("SELECT count(*) FROM client_fingerprint WHERE owner_hash = '" + owner + "'"));
    }

    @Test
    void erasingAPlayerDeletesTheirFingerprints() throws SQLException {
        String owner = "f7".repeat(64);
        submit(owner, "zq-erase:" + System.nanoTime());
        submit("f8".repeat(64), "zq-erase-other:" + System.nanoTime());

        delete("/profile/delete/" + owner, this.jwt.admin());

        assertEquals(0, count("SELECT count(*) FROM client_fingerprint WHERE owner_hash = '" + owner + "'"));
        assertTrue(count("SELECT count(*) FROM client_fingerprint WHERE owner_hash = '" + "f8".repeat(64) + "'") > 0, "other players keep theirs");
    }
}
