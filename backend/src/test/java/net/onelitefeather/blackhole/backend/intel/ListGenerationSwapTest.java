package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.intel.ListRefreshService.Outcome;
import net.onelitefeather.blackhole.backend.support.InMemoryListGenerationStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ListGenerationSwapTest {

    private static final String KEY = "x4bnet-vpn";

    private InMemoryListGenerationStore store;
    private TestClock clock;
    private ListRefreshService service;
    private IntelListSourceConfig source;
    private String body = "198.51.100.0/24\n";

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        this.store = new InMemoryListGenerationStore();
        this.clock = new TestClock();
        ListFetcher fetcher = new ListFetcher(
                host -> List.of(InetAddress.getByName("93.184.216.34")),
                (URI uri) -> new ListTransport.Reply(200, null,
                        new ByteArrayInputStream(this.body.getBytes(StandardCharsets.UTF_8))));
        this.service = new ListRefreshService(this.store, fetcher, this.clock);
        this.source = new IntelListSourceConfig(KEY);
        this.source.setKind(IntelClass.VPN);
        this.source.setUrl("https://lists.example.test/vpn/ipv4.txt");
        this.source.setEnabled(true);
    }

    @Test
    void aFailedWriteLeavesThePreviousGenerationActive() {
        assertEquals(Outcome.REFRESHED, this.service.refresh(this.source), "the first refresh succeeds");
        this.clock.advance(Duration.ofMinutes(11));
        this.body = "203.0.113.0/24\n";
        this.store.failWritesFor(KEY);

        Outcome second = this.service.refresh(this.source);

        assertEquals(Outcome.FAILED, second, "a failed write fails the refresh");
        assertEquals(1L, this.store.activeGenerations().get(KEY), "generation 1 stays the active one");
        assertEquals(Set.of(new ListGenerationStore.ListedCidr(KEY, "198.51.100.0/24")),
                Set.copyOf(this.store.loadActiveEntries()), "readers still see the previous entries");
        assertNotNull(this.store.lastError(KEY), "the failure is recorded on the source");
    }

    @Test
    void aSuccessfulRefreshSwitchesTheActiveGenerationExactlyOnce() {
        this.service.refresh(this.source);
        this.clock.advance(Duration.ofMinutes(11));
        this.body = "203.0.113.0/24\n";

        this.service.refresh(this.source);

        assertEquals(2, this.store.activations(), "one activation per successful refresh, none extra");
        assertEquals(2L, this.store.activeGenerations().get(KEY), "generation 2 is the active one");
    }

    @Test
    void theOldGenerationIsRemovedAfterTheSwitch() {
        this.service.refresh(this.source);
        this.clock.advance(Duration.ofMinutes(11));
        this.body = "203.0.113.0/24\n";

        this.service.refresh(this.source);

        assertEquals(Set.of(2L), this.store.generationNumbers(KEY), "only the new generation is kept");
    }

    @Test
    void aFailedRefreshDiscardsItsOwnPartialGeneration() {
        this.service.refresh(this.source);
        this.clock.advance(Duration.ofMinutes(11));
        this.store.failWritesFor(KEY);

        this.service.refresh(this.source);

        assertEquals(Set.of(1L), this.store.generationNumbers(KEY), "the partial generation 2 is discarded at once");
    }
}
