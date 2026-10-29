package net.onelitefeather.blackhole.backend.firewall;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasDiffTest {

    @Test
    void missingEntriesAreAddedAndExtraEntriesDeleted() {
        AliasDiff diff = AliasDiff.of(Set.of("203.0.113.7/32", "198.51.100.0/24"), Set.of("203.0.113.7/32", "192.0.2.0/24"));

        assertEquals(Set.of("198.51.100.0/24"), diff.toAdd(), "the missing range is added");
        assertEquals(Set.of("192.0.2.0/24"), diff.toDelete(), "the extra range is deleted");
    }

    @Test
    void anIdenticalSetYieldsAnEmptyDiff() {
        AliasDiff diff = AliasDiff.of(Set.of("203.0.113.7/32"), Set.of("203.0.113.7/32"));

        assertTrue(diff.isEmpty(), "no write when the alias already matches");
        assertEquals(0, diff.size(), "and the size is zero");
    }
}
