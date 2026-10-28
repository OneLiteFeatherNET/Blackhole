package net.onelitefeather.blackhole.backend.proxy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.proxy.dto.ProxyCapabilityDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyCapabilityServiceTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final InMemorySharedCache cache = new InMemorySharedCache();
    private final TestClock clock = new TestClock();
    private final ProxyCapabilityService service = new ProxyCapabilityService(cache, clock, STALE_AFTER, new SimpleMeterRegistry());

    private static ProxyCapabilityDTO.Report report(String proxyId, boolean present, String version) {
        return new ProxyCapabilityDTO.Report(proxyId, new ProxyCapabilityDTO.SignedVelocity(present, version));
    }

    private ProxyCapabilityDTO.Relay onlyRelay() {
        ProxyCapabilityDTO.Response response = service.capabilities();
        assertEquals(1, response.proxies().size(), "one proxy reported");
        return response.proxies().getFirst();
    }

    @Test
    void aReportIsStoredUnderTheProxy() {
        ProxyCapabilityDTO result = service.report(report("Proxy-1", true, "1.5.0"));

        assertEquals(new ProxyCapabilityDTO.Stored("Proxy-1", clock.instant()), result);
        assertTrue(cache.contains(CacheKeys.proxyCapability("Proxy-1")), "one key per proxy");
    }

    @Test
    void aPresentRelayIsShownAsPresentWithItsVersion() {
        service.report(report("Proxy-1", true, "1.5.0"));

        ProxyCapabilityDTO.Relay relay = onlyRelay();

        assertEquals(ProxyCapabilityDTO.RelayState.PRESENT, relay.state(), "present");
        assertEquals("1.5.0", relay.version(), "the version is shown");
    }

    @Test
    void aMissingRelayIsShownAsAbsent() {
        service.report(report("Proxy-1", false, null));

        assertEquals(ProxyCapabilityDTO.RelayState.ABSENT, onlyRelay().state(), "absent is never shown as present");
    }

    @Test
    void aReportWithinTheStaleWindowKeepsItsState() {
        service.report(report("Proxy-1", false, null));
        clock.advance(STALE_AFTER.minusSeconds(1));

        assertEquals(ProxyCapabilityDTO.RelayState.ABSENT, onlyRelay().state(), "still the last report");
    }

    @Test
    void aReportOlderThanTheStaleWindowIsUnknown() {
        service.report(report("Proxy-1", true, "1.5.0"));
        clock.advance(STALE_AFTER.plusSeconds(1));

        assertEquals(ProxyCapabilityDTO.RelayState.UNKNOWN, onlyRelay().state(), "a proxy that stopped reporting is unknown, not present");
    }

    @Test
    void withoutAnyReportNoProxyIsListed() {
        assertTrue(service.capabilities().proxies().isEmpty(), "nothing has reported");
    }

    @Test
    void aProxyIdWithCharactersOutsideTheAllowedSetIsRefused() {
        ProxyCapabilityDTO result = service.report(report("proxy/../1", true, "1.5.0"));

        ProxyCapabilityDTO.Error error = assertInstanceOf(ProxyCapabilityDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(ErrorKind.VALIDATION, error.kind(), error.errorMessage());
        assertFalse(cache.contains(CacheKeys.proxyCapability("proxy/../1")), "nothing is stored for a refused report");
    }

    @Test
    void aReportThatCannotBeStoredWhileTheCacheIsDownIsRefusedAsUnavailable() {
        cache.goDown();

        ProxyCapabilityDTO result = service.report(report("Proxy-1", true, "1.5.0"));

        ProxyCapabilityDTO.Error error = assertInstanceOf(ProxyCapabilityDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(ErrorKind.UNAVAILABLE, error.kind(), "the proxy retries at its next heartbeat");
    }
}
