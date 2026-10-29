package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.support.InMemoryListGenerationStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListGenerationSweeperTest {

    private static final String KEY = "x4bnet-vpn";

    private InMemoryListGenerationStore store;
    private TestClock clock;
    private ListGenerationSweeper sweeper;

    @BeforeEach
    void setUp() {
        this.store = new InMemoryListGenerationStore();
        this.clock = new TestClock();
        this.sweeper = new ListGenerationSweeper(this.store, this.clock);
    }

    @Test
    void anOrphanedGenerationOlderThanAnHourIsRemoved() {
        long orphan = this.store.openGeneration(KEY, this.clock.millis());
        this.store.writeEntries(KEY, orphan, List.of("198.51.100.0/24"));
        this.clock.advance(Duration.ofHours(2));

        int removed = this.sweeper.sweepNow();

        assertEquals(1, removed, "one orphaned generation is removed");
        assertEquals(Set.of(), this.store.generationNumbers(KEY), "the orphan is no longer stored");
    }

    @Test
    void aGenerationThatIsStillBeingWrittenIsKept() {
        this.store.openGeneration(KEY, this.clock.millis());
        this.clock.advance(Duration.ofMinutes(10));

        assertEquals(0, this.sweeper.sweepNow(), "a generation opened ten minutes ago may still be in progress");
        assertEquals(Set.of(1L), this.store.generationNumbers(KEY), "the in-progress generation stays");
    }

    @Test
    void theActiveGenerationIsNeverRemoved() {
        long active = this.store.openGeneration(KEY, this.clock.millis());
        this.store.activate(KEY, active, this.clock.millis());
        this.clock.advance(Duration.ofDays(3));

        assertEquals(0, this.sweeper.sweepNow(), "the active generation is not an orphan, however old");
        assertEquals(Set.of(active), this.store.generationNumbers(KEY), "the active generation stays");
    }
}
