package net.onelitefeather.blackhole.velocity.clientrule;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraitCollectorTest {

    private static final String OWNER = "ab".repeat(64);

    private final TraitCollector collector = new TraitCollector();

    @Test
    void anUnknownPlayerHasNoTraits() {
        assertTrue(collector.traits(OWNER).isEmpty());
    }

    @Test
    void theEventsMayArriveInAnyOrder() {
        collector.channels(OWNER, List.of("fabric:registry/sync"));
        collector.brand(OWNER, "fabric");
        collector.loggedIn(OWNER, 767);
        collector.mods(OWNER, List.of("jei@4.16"));

        ClientTraits traits = collector.traits(OWNER).orElseThrow();

        assertEquals(767, traits.protocol());
        assertEquals("fabric", traits.brand());
        assertEquals(Set.of("fabric:registry/sync"), traits.channels());
        assertEquals(Set.of("jei@4.16"), traits.mods());
    }

    @Test
    void aTraitBeforeTheLoginKeepsTheProtocolUnknownUntilTheLogin() {
        collector.brand(OWNER, "vanilla");

        assertEquals(-1, collector.traits(OWNER).orElseThrow().protocol());
        collector.loggedIn(OWNER, 765);
        assertEquals(765, collector.traits(OWNER).orElseThrow().protocol());
    }

    @Test
    void channelsAccumulateAcrossRegistrations() {
        collector.channels(OWNER, List.of("a:one"));
        collector.channels(OWNER, List.of("b:two", "a:one"));

        assertEquals(Set.of("a:one", "b:two"), collector.traits(OWNER).orElseThrow().channels());
    }

    @Test
    void aLaterBrandReplacesTheEarlierOne() {
        collector.brand(OWNER, "vanilla");
        collector.brand(OWNER, "fabric");

        assertEquals("fabric", collector.traits(OWNER).orElseThrow().brand());
    }

    @Test
    void forgettingDropsEverything() {
        collector.loggedIn(OWNER, 767);
        collector.brand(OWNER, "fabric");

        collector.forget(OWNER);

        assertTrue(collector.traits(OWNER).isEmpty());
        assertEquals(0, collector.size());
    }

    @Test
    void aClientCannotMakeTheProxyHoldMoreThanTheCapOrOverlongValues() {
        collector.channels(OWNER, IntStream.range(0, 2000).mapToObj(i -> "c:" + i).toList());
        collector.channels(OWNER, List.of("x".repeat(129)));
        collector.brand(OWNER, "b".repeat(500));

        ClientTraits traits = collector.traits(OWNER).orElseThrow();

        assertEquals(ClientTraits.MAX_ENTRIES, traits.channels().size());
        assertFalse(traits.channels().contains("x".repeat(129)));
        assertEquals(ClientTraits.MAX_LENGTH, traits.brand().length());
    }

    @Test
    void theLogSafeSummaryNamesNoTrait() {
        collector.brand(OWNER, "secret-brand");
        collector.channels(OWNER, List.of("secret:channel"));

        assertFalse(collector.traits(OWNER).orElseThrow().toString().contains("secret"));
    }
}
