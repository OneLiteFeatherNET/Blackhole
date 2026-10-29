package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The /intel routes against the real application: the proxy writes, staff reads, the proxy is refused on reads. */
@Tag("integration")
class IntelControllerTest extends ApiIntegrationTest {

    private static final String OWNER = "d".repeat(128);

    private static Map<String, Object> startBody(String sessionId, String owner) {
        return Map.of("sessionId", sessionId, "owner", owner, "playerUuid", UUID.randomUUID().toString(),
                "playerName", "Steve", "ip", "192.0.2.10", "protocolVersion", 767, "virtualHost", "play.example.net",
                "outcome", "ALLOWED");
    }

    @Test
    void theProxyStartsASessionAndTheStoredRecordComesBack() {
        Reply reply = post("/intel/sessions", startBody(UUID.randomUUID().toString(), OWNER), jwt.proxy());

        assertEquals(200, reply.status(), reply.body());
        assertEquals("ALLOWED", reply.json().get("outcome"));
    }

    @Test
    void aStaffMemberCannotReportASession() {
        Reply reply = post("/intel/sessions", startBody(UUID.randomUUID().toString(), OWNER), jwt.admin());

        assertEquals(403, reply.status(), "only the proxy writes sessions");
    }

    @Test
    void aMalformedOwnerIsRejectedWithAValidationError() {
        Reply reply = post("/intel/sessions", startBody(UUID.randomUUID().toString(), "short"), jwt.proxy());

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void aViewerReadsTheSessionsOfAPlayerNewestFirst() {
        String sessionId = UUID.randomUUID().toString();
        post("/intel/sessions", startBody(sessionId, OWNER), jwt.proxy());

        Reply reply = get("/intel/players/" + OWNER + "/sessions", jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(sessionId, reply.json().get("sessions") instanceof java.util.List<?> list
                ? ((Map<?, ?>) list.getFirst()).get("sessionId") : null, "the stored session is listed");
    }

    @Test
    void theProxyMayNotReadSessionsBack() {
        Reply reply = get("/intel/players/" + OWNER + "/sessions", jwt.proxy());

        assertEquals(403, reply.status(), "a proxy cannot read the sessions back");
    }

    @Test
    void aSessionUpdateForAnUnknownSessionIsNotFound() {
        Reply reply = patch("/intel/sessions/" + UUID.randomUUID(), Map.of("ended", true), jwt.proxy());

        assertEquals(404, reply.status(), reply.body());
    }
}
