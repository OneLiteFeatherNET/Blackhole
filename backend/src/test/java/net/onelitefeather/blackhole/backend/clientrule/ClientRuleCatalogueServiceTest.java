package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleCatalogueDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleCatalogueServiceTest {

    private final InMemorySharedCache cache = new InMemorySharedCache();
    private final TestClock clock = new TestClock();
    private final ClientRuleCatalogueService service = new ClientRuleCatalogueService(cache, clock, Duration.ofHours(24), 3);

    private static ClientRuleCatalogueDTO.Entry entry(String name, int players) {
        return new ClientRuleCatalogueDTO.Entry(name, players);
    }

    private static ClientRuleCatalogueDTO.ReportRequest report(String proxyId, List<ClientRuleCatalogueDTO.Entry> brands,
                                                              List<ClientRuleCatalogueDTO.Entry> channels, List<String> commands) {
        return new ClientRuleCatalogueDTO.ReportRequest(proxyId, brands, channels, commands,
                List.of(new ClientRuleCatalogueDTO.ProtocolEntry(767, "1.21")));
    }

    private ClientRuleCatalogueDTO.Error expectError(ClientRuleCatalogueDTO result, ErrorKind kind) {
        ClientRuleCatalogueDTO.Error error = assertInstanceOf(ClientRuleCatalogueDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(kind, error.kind(), error.errorMessage());
        return error;
    }

    @Test
    void aReportIsStoredUnderItsProxyKey() {
        ClientRuleCatalogueDTO result = service.report(report("Proxy-1", List.of(entry("fabric", 10)), List.of(), List.of("hub")));

        assertEquals(new ClientRuleCatalogueDTO.Stored("Proxy-1", clock.instant()), result);
        assertTrue(cache.contains(CacheKeys.clientRuleCatalogue("Proxy-1")), "one key per proxy");
    }

    @Test
    void withoutAReportTheCatalogueIsNotAvailable() {
        ClientRuleCatalogueDTO.Response response = service.catalogue();

        assertFalse(response.available());
        assertTrue(response.brands().isEmpty());
    }

    @Test
    void brandsAndChannelsSeenFromFewerThanThreePlayersAreNeverStored() {
        service.report(report("Proxy-1",
                List.of(entry("fabric", 3), entry("lonely-client", 2)),
                List.of(entry("meteor-client:main", 5), entry("rare:channel", 1)),
                List.of()));

        ClientRuleCatalogueDTO.Response response = service.catalogue();

        assertEquals(List.of("fabric"), response.brands().stream().map(ClientRuleCatalogueDTO.Entry::name).toList(), "k-anonymity on brands");
        assertEquals(List.of("meteor-client:main"), response.channels().stream().map(ClientRuleCatalogueDTO.Entry::name).toList(), "k-anonymity on channels");
        ClientRuleCatalogueSnapshot stored = cache.get(CacheKeys.clientRuleCatalogue("Proxy-1"),
                io.micronaut.core.type.Argument.of(ClientRuleCatalogueSnapshot.class)).orElseThrow();
        assertEquals(List.of("meteor-client:main"), stored.channels().stream().map(ClientRuleCatalogueDTO.Entry::name).toList(),
                "the rare channel never reached the cache");
    }

    @Test
    void theThresholdIsConfigurable() {
        ClientRuleCatalogueService strict = new ClientRuleCatalogueService(cache, clock, Duration.ofHours(24), 10);
        strict.report(report("Proxy-1", List.of(entry("fabric", 9), entry("vanilla", 10)), List.of(), List.of()));

        assertEquals(List.of("vanilla"), strict.catalogue().brands().stream().map(ClientRuleCatalogueDTO.Entry::name).toList());
    }

    @Test
    void commandsAndProtocolsComeThroughSortedAndWithoutDuplicates() {
        service.report(report("Proxy-1", List.of(), List.of(), List.of("server", "hub", "hub", "minecraft:op")));
        service.report(report("Proxy-2", List.of(), List.of(), List.of("hub", "alpha")));

        ClientRuleCatalogueDTO.Response response = service.catalogue();

        assertEquals(List.of("alpha", "hub", "minecraft:op", "server"), response.commands());
        assertEquals(List.of(new ClientRuleCatalogueDTO.ProtocolEntry(767, "1.21")), response.protocols());
        assertEquals(List.of("Proxy-1", "Proxy-2"), response.proxies());
    }

    @Test
    void playersOfTheSameBrandAddUpAcrossProxies() {
        service.report(report("Proxy-1", List.of(entry("fabric", 4)), List.of(), List.of()));
        service.report(report("Proxy-2", List.of(entry("fabric", 5)), List.of(), List.of()));

        assertEquals(List.of(entry("fabric", 9)), service.catalogue().brands());
    }

    @Test
    void aSnapshotOlderThanTheTimeToLiveIsIgnored() {
        service.report(report("Proxy-1", List.of(entry("fabric", 4)), List.of(), List.of()));

        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertFalse(service.catalogue().available(), "a dead proxy's snapshot expires");
    }

    @Test
    void aNameOutsideTheRuleCharsetIsRejected() {
        expectError(service.report(report("Proxy-1", List.of(entry("Fabric With Space", 5)), List.of(), List.of())), ErrorKind.VALIDATION);
        expectError(service.report(report("Proxy-1", List.of(), List.of(), List.of("hello world"))), ErrorKind.VALIDATION);
        expectError(service.report(report("bad id!", List.of(), List.of(), List.of())), ErrorKind.VALIDATION);
        assertFalse(cache.contains(CacheKeys.clientRuleCatalogue("Proxy-1")), "nothing stored");
    }

    @Test
    void anOversizedSnapshotIsRejected() {
        List<String> commands = java.util.stream.IntStream.range(0, ClientRuleCatalogueService.MAX_COMMANDS + 1).mapToObj(i -> "c" + i).toList();

        expectError(service.report(report("Proxy-1", List.of(), List.of(), commands)), ErrorKind.VALIDATION);
    }

    @Test
    void aCacheOutageFailsTheReportWithUnavailable() {
        cache.goDown();

        expectError(service.report(report("Proxy-1", List.of(entry("fabric", 5)), List.of(), List.of())), ErrorKind.UNAVAILABLE);
        assertFalse(service.catalogue().available(), "and the read says unavailable instead of failing");
    }
}
