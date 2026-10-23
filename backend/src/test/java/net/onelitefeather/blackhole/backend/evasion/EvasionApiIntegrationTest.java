package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class EvasionApiIntegrationTest extends ApiIntegrationTest {

    private static final String ME = "e1".repeat(64);
    private static final String ALT = "e2".repeat(64);

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.evasion.ip-salt", "test-salt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoOwnersOnTheSameIpAreLinkedWithoutLeakingTheIp() {
        assertEquals(200, post("/evasion/record", Map.of("owner", ME, "ip", "203.0.113.7")).status());
        assertEquals(200, post("/evasion/record", Map.of("owner", ALT, "ip", "203.0.113.7")).status());

        Reply reply = get("/evasion/" + ME + "/sightings", this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertFalse(reply.body().contains("203.0.113.7"), "the raw IP must never be returned");
        Map<String, Object> body = reply.json();
        assertEquals(true, body.get("detectionEnabled"));
        List<Map<String, Object>> linked = (List<Map<String, Object>>) body.get("linkedOwners");
        assertEquals(1, linked.size(), reply.body());
        assertEquals(ALT, linked.getFirst().get("owner"));
        assertEquals("SHARED_IP", linked.getFirst().get("reason"));
        assertFalse(linked.getFirst().containsKey("token"), "the hashed address is not exposed");
    }

    @Test
    void anOwnerNobodyShareAnAddressWithHasNone() {
        Reply reply = get("/evasion/" + "e3".repeat(64) + "/sightings", this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertTrue(((List<?>) reply.json().get("linkedOwners")).isEmpty());
    }

    @Test
    void aMalformedOwnerIsRejected() {
        assertEquals(400, get("/evasion/not-a-hash/sightings", this.jwt.viewer()).status());
    }

    @Test
    void aProxyTokenMayNotReadSightingsAndAnAnonymousCallIsUnauthorized() {
        assertEquals(403, get("/evasion/" + ME + "/sightings", this.jwt.proxy()).status());
        assertEquals(401, get("/evasion/" + ME + "/sightings", null).status());
    }
}
