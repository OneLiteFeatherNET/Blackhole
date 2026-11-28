package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.intel.ListRefreshService.Outcome;
import net.onelitefeather.blackhole.backend.support.InMemoryListGenerationStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListRefreshDebounceTest {

    private InMemoryListGenerationStore store;
    private TestClock clock;
    private AtomicInteger downloads;
    private ListRefreshService service;
    private IntelListSourceConfig source;

    @BeforeEach
    void setUp() throws Exception {
        this.store = new InMemoryListGenerationStore();
        this.clock = new TestClock();
        this.downloads = new AtomicInteger();
        ListFetcher fetcher = new ListFetcher(
                host -> List.of(InetAddress.getByName("93.184.216.34")),
                uri -> {
                    this.downloads.incrementAndGet();
                    return new ListTransport.Reply(200, null,
                            new ByteArrayInputStream("198.51.100.0/24\n".getBytes(StandardCharsets.UTF_8)));
                });
        this.service = new ListRefreshService(this.store, fetcher, this.clock);
        this.source = new IntelListSourceConfig("x4bnet-vpn");
        this.source.setKind(IntelClass.VPN);
        this.source.setUrl("https://lists.example.test/vpn/ipv4.txt");
        this.source.setEnabled(true);
    }

    @Test
    void aSecondRefreshWithinTenMinutesIsSkipped() {
        assertEquals(Outcome.REFRESHED, this.service.refresh(this.source), "the first refresh runs");

        this.clock.advance(Duration.ofMinutes(2));

        assertEquals(Outcome.SKIPPED, this.service.refresh(this.source), "a refresh inside the debounce window does not run");
        assertEquals(1, this.downloads.get(), "the list is downloaded once");
    }

    @Test
    void aRefreshAfterTheWindowRunsAgain() {
        this.service.refresh(this.source);

        this.clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertEquals(Outcome.REFRESHED, this.service.refresh(this.source), "a refresh after the window runs");
        assertEquals(2, this.downloads.get(), "the list is downloaded again");
    }
}
