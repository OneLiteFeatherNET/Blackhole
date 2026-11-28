package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The /guard routes against the real application: staff set the manual level, the proxy reports its presence. */
@Tag("integration")
class GuardControllerTest extends ApiIntegrationTest {

    @Test
    void aModeratorSetsTheManualLevelAndTheResponseShowsManualAndUnattendedSources() {
        Reply reply = post("/guard/level", Map.of("level", "LOCKDOWN"), jwt.moderator());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> json = reply.json();
        assertEquals("LOCKDOWN", json.get("manualLevel"), "the level the moderator set is the manual level");
        assertNotNull(json.get("automaticLevel"), "the unattended level is shown next to the manual one");
        assertEquals("LOCKDOWN", json.get("effectiveLevel"), "manual LOCKDOWN is the stricter of the two, so it is in force");
    }

    @Test
    void aLevelOutsideOffStrictLockdownIsRefusedWithAValidationError() {
        Reply reply = post("/guard/level", Map.of("level", "PANIC"), jwt.moderator());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("VALIDATION", reply.json().get("kind"), "an unknown level is a validation error");
    }

    @Test
    void theProxyPresenceIsAcceptedAndTheAnswerCarriesNoPlayerData() {
        Reply reply = post("/guard/presence", Map.of("proxyId", "proxy-" + UUID.randomUUID().toString().substring(0, 8),
                "staffOnline", 0), jwt.proxy());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(Set.of("staffPresent", "effectiveLevel"), reply.json().keySet(),
                "the answer holds only whether staff are present and the level in force");
    }
}
