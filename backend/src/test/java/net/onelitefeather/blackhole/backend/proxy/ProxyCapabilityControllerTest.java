package net.onelitefeather.blackhole.backend.proxy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.proxy.controller.ProxyCapabilityController;
import net.onelitefeather.blackhole.backend.proxy.dto.ProxyCapabilityDTO;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProxyCapabilityControllerTest {

    private final InMemorySharedCache cache = new InMemorySharedCache();
    private final ProxyCapabilityController controller = new ProxyCapabilityController(
            new ProxyCapabilityService(cache, new TestClock(), Duration.ofMinutes(10), new SimpleMeterRegistry()));

    private static ProxyCapabilityDTO.Report report(String proxyId) {
        return new ProxyCapabilityDTO.Report(proxyId, new ProxyCapabilityDTO.SignedVelocity(true, "1.5.0"));
    }

    @Test
    void aStoredReportIsAnsweredWithNoContent() {
        HttpResponse<ProxyCapabilityDTO> response = controller.report(report("Proxy-1"));

        assertEquals(HttpStatus.NO_CONTENT, response.status(), "the report is stored and has no body to return");
    }

    @Test
    void aRefusedReportIsAnsweredWithTheStatusOfItsErrorKind() {
        HttpResponse<ProxyCapabilityDTO> response = controller.report(report("bad id"));

        assertEquals(HttpStatus.BAD_REQUEST, response.status(), "a malformed proxy id is a validation error");
        assertNotNull(response.body(), "the error variant explains the refusal");
    }

    @Test
    void aReportWhileTheCacheIsDownIsAnsweredServiceUnavailable() {
        cache.goDown();

        HttpResponse<ProxyCapabilityDTO> response = controller.report(report("Proxy-1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status(), "the proxy retries at its next heartbeat");
    }

    @Test
    void theStateReportedThroughTheControllerIsReadBackThroughIt() {
        controller.report(report("Proxy-1"));

        ProxyCapabilityDTO.Response body = controller.get().body();

        assertEquals(1, body.proxies().size(), "the reporting proxy is listed");
        assertEquals("Proxy-1", body.proxies().getFirst().proxyId());
    }
}
