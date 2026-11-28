package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    /** A fresh 128-character owner per test, so no test depends on another's rows. */
    private static String freshOwner() {
        return "%s%s%s%s".formatted(UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString().replace("-", ""));
    }

    private static Map<String, Object> startBodyWithName(String owner, String playerName) {
        return Map.of("sessionId", UUID.randomUUID().toString(), "owner", owner, "playerUuid", UUID.randomUUID().toString(),
                "playerName", playerName, "ip", "192.0.2.10", "outcome", "ALLOWED");
    }

    @Test
    void aViewerReadsTheNamesOfAnAccountNewestFirst() {
        String owner = freshOwner();
        post("/intel/sessions", startBodyWithName(owner, "Steve"), jwt.proxy());
        post("/intel/sessions", startBodyWithName(owner, "Alex"), jwt.proxy());

        Reply reply = get("/intel/players/" + owner + "/names", jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        List<?> names = (List<?>) reply.json().get("names");
        assertEquals(List.of("Alex", "Steve"), names.stream().map(n -> ((Map<?, ?>) n).get("name")).toList(),
                "both names, the newest first");
    }

    @Test
    void theProxyMayReadTheNamesOfAnAccount() {
        Reply reply = get("/intel/players/" + freshOwner() + "/names", jwt.proxy());

        assertEquals(200, reply.status(), "the proxy reads names for the command");
    }

    @Test
    void aMalformedOwnerIsRejectedForTheNames() {
        Reply reply = get("/intel/players/short/names", jwt.viewer());

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void anOldNameFindsTheAccountWithItsCurrentName() {
        String owner = freshOwner();
        String oldName = "Old" + freshOwner().substring(0, 8);
        String newName = "New" + freshOwner().substring(0, 8);
        post("/intel/sessions", startBodyWithName(owner, oldName), jwt.proxy());
        post("/intel/sessions", startBodyWithName(owner, newName), jwt.proxy());

        Reply reply = get("/intel/names/" + oldName.toLowerCase() + "/owners", jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        List<?> owners = (List<?>) reply.json().get("owners");
        Map<?, ?> found = owners.stream().map(o -> (Map<?, ?>) o).filter(o -> owner.equals(o.get("owner"))).findFirst().orElseThrow();
        assertEquals(newName, found.get("currentName"), "the owner's newest name is shown for the old-name match");
    }

    @Test
    void anUnknownNameIsAnEmptyOwnerList() {
        Reply reply = get("/intel/names/Unknown" + freshOwner().substring(0, 8) + "/owners", jwt.proxy());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of(), reply.json().get("owners"), "no owner matches, and the empty list is sent, not omitted");
    }

    @Test
    void aSessionUpdateForAnUnknownSessionIsNotFound() {
        Reply reply = patch("/intel/sessions/" + UUID.randomUUID(), Map.of("ended", true), jwt.proxy());

        assertEquals(404, reply.status(), reply.body());
    }
}
