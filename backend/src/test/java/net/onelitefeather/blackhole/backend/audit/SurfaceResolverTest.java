package net.onelitefeather.blackhole.backend.audit;

import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceResolverTest {

    private static final UUID OID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void proxyVouchingForStaffGivesInGame() {
        Actor proxy = new Actor(OID, null, ActorKind.SERVICE, true);

        assertEquals(Surface.IN_GAME, SurfaceResolver.resolve(proxy, true));
    }

    @Test
    void humanWithPanelHeaderGivesPanel() {
        Actor human = new Actor(OID, "Mod", ActorKind.HUMAN, false).withPanel(true);

        assertEquals(Surface.PANEL, SurfaceResolver.resolve(human, false));
    }

    @Test
    void humanWithoutPanelHeaderGivesApi() {
        Actor human = new Actor(OID, "Mod", ActorKind.HUMAN, false);

        assertEquals(Surface.API, SurfaceResolver.resolve(human, false));
    }

    @Test
    void proxyWithoutOnBehalfOfGivesApi() {
        Actor proxy = new Actor(OID, null, ActorKind.SERVICE, true);

        assertEquals(Surface.API, SurfaceResolver.resolve(proxy, false));
    }
}
