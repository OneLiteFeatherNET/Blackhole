package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.active;
import static net.onelitefeather.blackhole.velocity.TestData.cleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentMirrorTest {

    private final PunishmentMirror mirror = new PunishmentMirror();

    @Test
    void aPlayerIsNotLoadedUntilTheirStateWasRead() {
        assertFalse(mirror.isLoaded(OWNER));
    }

    @Test
    void loadingMarksThePlayerLoadedEvenWithoutAnyPunishment() {
        mirror.load(OWNER, List.of());

        assertTrue(mirror.isLoaded(OWNER), "an empty state is a definitive answer");
        assertTrue(mirror.active(OWNER).isEmpty());
    }

    @Test
    void aSetMessageOccupiesItsSectionOfALoadedPlayer() {
        mirror.load(OWNER, List.of());

        mirror.apply(active("chat", EnforcementKind.MUTE, "p1", null));

        assertEquals(1, mirror.active(OWNER).size());
    }

    @Test
    void aSetMessageForAPlayerThatIsNotLoadedIsIgnored() {
        mirror.apply(active("chat", EnforcementKind.MUTE, "p1", null));

        assertFalse(mirror.isLoaded(OWNER), "players of other proxies must not pile up in the mirror");
    }

    @Test
    void aClearedMessageEmptiesItsSection() {
        mirror.load(OWNER, List.of(active("chat", EnforcementKind.MUTE, "p1", null)));

        mirror.apply(cleared("chat", EnforcementKind.MUTE, "p1"));

        assertTrue(mirror.active(OWNER).isEmpty());
    }

    @Test
    void aClearedMessageOfAnOlderPunishmentLeavesTheNewerOneInPlace() {
        mirror.load(OWNER, List.of(active("chat", EnforcementKind.MUTE, "p2", null)));

        mirror.apply(cleared("chat", EnforcementKind.MUTE, "p1"));

        assertEquals("p2", mirror.active(OWNER).getFirst().punishmentIdentifier(), "the older mute's expiry must not unmute the new one");
    }

    @Test
    void aMuteMessageDoesNotTouchTheBanSection() {
        mirror.load(OWNER, List.of(active("network", EnforcementKind.BAN, "ban", null)));

        mirror.apply(active("chat", EnforcementKind.MUTE, "mute", null));

        assertEquals(2, mirror.active(OWNER).size(), "one slot per section");
    }

    @Test
    void forgettingAPlayerUnloadsThem() {
        mirror.load(OWNER, List.of());

        mirror.forget(OWNER);

        assertFalse(mirror.isLoaded(OWNER));
    }
}
