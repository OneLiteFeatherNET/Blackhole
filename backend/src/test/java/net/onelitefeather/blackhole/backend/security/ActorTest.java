package net.onelitefeather.blackhole.backend.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorTest {

    private static final UUID INGAME = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Actor STAFF = new Actor(UUID.fromString("66666666-6666-4666-8666-666666666666"), "Staff", ActorKind.HUMAN, false);
    private static final Actor PROXY = new Actor(UUID.fromString("77777777-7777-4777-8777-777777777777"), "Proxy", ActorKind.SERVICE, true);

    @Test
    void withoutOnBehalfOfTheCallerIsBothSourceAndRecorder() {
        Attribution attribution = STAFF.attribution(null).orElseThrow();

        assertEquals(new Attribution(STAFF.oid(), STAFF.oid()), attribution);
    }

    @Test
    void theProxyMayNameAnInGameStaffMemberButIsStillTheRecorder() {
        Attribution attribution = PROXY.attribution(INGAME).orElseThrow();

        assertEquals(INGAME, attribution.source());
        assertEquals(PROXY.oid(), attribution.recordedByOid());
    }

    @Test
    void anyoneButTheProxyNamingSomeoneElseGetsNoAttribution() {
        assertTrue(STAFF.attribution(INGAME).isEmpty(), "a moderator cannot vouch for anyone");
    }
}
