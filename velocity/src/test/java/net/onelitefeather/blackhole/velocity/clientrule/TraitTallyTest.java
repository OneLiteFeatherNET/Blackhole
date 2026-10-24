package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueEntry;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraitTallyTest {

    private final MutableClock clock = new MutableClock();
    private final TraitTally tally = new TraitTally(clock, Duration.ofHours(24));

    private static ClientTraits with(String brand, String... channels) {
        return ClientTraits.unknown().withBrand(brand).withChannels(List.of(channels));
    }

    private static List<String> names(List<ClientRuleCatalogueEntry> entries) {
        return entries.stream().map(ClientRuleCatalogueEntry::getName).toList();
    }

    @Test
    void aValueSeenFromTwoPlayersIsNotListedAndFromThreeIs() {
        tally.seen("p1", with("fabric", "rare:channel"));
        tally.seen("p2", with("fabric", "rare:channel"));
        assertEquals(List.of(), names(tally.snapshot(3).brands()));

        tally.seen("p3", with("fabric"));

        assertEquals(List.of("fabric"), names(tally.snapshot(3).brands()));
        assertEquals(List.of(), names(tally.snapshot(3).channels()), "the channel is still shown by two only");
    }

    @Test
    void theSamePlayerCountsOnceHoweverOftenTheyAreSeen() {
        for (int i = 0; i < 5; i++) {
            tally.seen("p1", with("lunarclient"));
        }

        assertEquals(List.of(), names(tally.snapshot(3).brands()));
    }

    @Test
    void theEntriesCarryTheNumberOfDistinctPlayers() {
        for (String player : List.of("p1", "p2", "p3", "p4")) {
            tally.seen(player, with("fabric"));
        }

        assertEquals(4, tally.snapshot(3).brands().getFirst().getPlayers());
    }

    @Test
    void sightingsOlderThanTheWindowStopCounting() {
        tally.seen("p1", with("fabric"));
        tally.seen("p2", with("fabric"));
        clock.advance(Duration.ofHours(23));
        tally.seen("p3", with("fabric"));
        assertEquals(1, tally.snapshot(3).brands().size());

        clock.advance(Duration.ofHours(2));

        assertEquals(List.of(), names(tally.snapshot(3).brands()), "two of three are older than a day");
    }

    @Test
    void namesAreLowerCasedAndValuesTheBackendWouldRefuseAreNeverCounted() {
        for (String player : List.of("p1", "p2", "p3")) {
            tally.seen(player, with("Fabric", "Has Space:x", "ok:chan", "minecraft:brand"));
        }

        assertEquals(List.of("fabric"), names(tally.snapshot(3).brands()));
        assertEquals(List.of("ok:chan"), names(tally.snapshot(3).channels()), "no spaces, no minecraft namespace");
    }

    @Test
    void theSnapshotHoldsNamesAndCountsOnly() {
        tally.seen("owner-hash-1", with("fabric"));
        tally.seen("owner-hash-2", with("fabric"));
        tally.seen("owner-hash-3", with("fabric"));

        assertTrue(!tally.snapshot(3).toString().contains("owner-hash"));
    }
}
