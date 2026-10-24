package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueEntry;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueProtocol;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueReportRequest;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleCatalogueReporterTest {

    private static final Duration DEBOUNCE = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock();
    private final TraitTally tally = new TraitTally(clock, Duration.ofHours(24));
    private final FakeClientRuleBackend backend = new FakeClientRuleBackend();
    private final ManualTimer timer = new ManualTimer();
    private final ClientRuleCatalogueReporter reporter = new ClientRuleCatalogueReporter(
            tally, () -> Set.of("hub", "minecraft:op"), backend, timer, "Proxy-1", 3, DEBOUNCE, HEARTBEAT);

    private void show(String brand, int players) {
        for (int i = 0; i < players; i++) {
            tally.seen("p" + i, ClientTraits.unknown().withBrand(brand).withChannels(List.of(brand + ":chan")));
        }
    }

    @Test
    void startPushesAFullSnapshotUnderTheProxyId() {
        show("fabric", 3);
        reporter.start();

        timer.runDue();

        assertEquals(1, backend.catalogues.size());
        ClientRuleCatalogueReportRequest report = backend.catalogues.getFirst();
        assertEquals("Proxy-1", report.getProxyId());
        assertEquals(List.of("fabric"), report.getBrands().stream().map(ClientRuleCatalogueEntry::getName).toList());
        assertEquals(List.of("fabric:chan"), report.getChannels().stream().map(ClientRuleCatalogueEntry::getName).toList());
    }

    @Test
    void aBrandShownByTwoPlayersIsNotReported() {
        show("lonely", 2);
        reporter.start();

        timer.runDue();

        assertEquals(List.of(), backend.catalogues.getFirst().getBrands());
        assertEquals(List.of(), backend.catalogues.getFirst().getChannels());
    }

    @Test
    void theCommandsComeFromTheCommandTreeSupplierAndTheProtocolsFromTheVersionsTheProxySupports() {
        reporter.start();

        timer.runDue();

        ClientRuleCatalogueReportRequest report = backend.catalogues.getFirst();
        assertEquals(List.of("hub", "minecraft:op"), report.getCommands().stream().sorted().toList());
        assertFalse(report.getProtocols().isEmpty());
        assertTrue(report.getProtocols().stream().allMatch(protocol -> protocol.getVersion() >= 0 && protocol.getName().matches("[A-Za-z0-9._ -]{1,32}")),
                "names the backend accepts");
        assertEquals(report.getProtocols().stream().map(ClientRuleCatalogueProtocol::getVersion).distinct().count(), report.getProtocols().size(),
                "one entry per protocol number");
    }

    @Test
    void aChangeIsPushedOnceAfterTheDebounceNoMatterHowOften() {
        reporter.start();
        timer.runDue();
        backend.catalogues.clear();

        reporter.changed();
        reporter.changed();
        reporter.changed();
        timer.advance(DEBOUNCE.minusSeconds(1));
        assertTrue(backend.catalogues.isEmpty());
        timer.advance(Duration.ofSeconds(1));

        assertEquals(1, backend.catalogues.size());
    }

    @Test
    void theHeartbeatPushesAgain() {
        reporter.start();
        timer.runDue();
        backend.catalogues.clear();

        timer.advance(HEARTBEAT);

        assertEquals(1, backend.catalogues.size());
    }

    @Test
    void aFailedPushIsOnlyLoggedAndTheNextOneSendsAFullSnapshotAgain() {
        backend.catalogueFailure = new ApiException(503, "cache down");
        reporter.start();
        timer.runDue();
        assertTrue(backend.catalogues.isEmpty());
        backend.catalogueFailure = null;

        timer.advance(HEARTBEAT);

        assertEquals(1, backend.catalogues.size());
    }
}
