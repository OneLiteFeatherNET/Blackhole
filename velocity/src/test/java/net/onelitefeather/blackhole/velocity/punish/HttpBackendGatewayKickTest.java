package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kick calls reach the backend as HTTP with the proxy's own token; nothing is sent over the bus. */
class HttpBackendGatewayKickTest {

    private static final TokenSource TOKEN = new TokenSource() {
        @Override
        public String token() {
            return "proxy-token";
        }

        @Override
        public void rejected(String token) {
        }
    };

    private static HttpBackendGateway gateway(FakeHttpClient transport) {
        ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport);
        return new HttpBackendGateway(client, Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void anAcknowledgementIsPostedToTheDeliveriesRouteWithTheProxyToken() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(204, "");

        gateway(transport).acknowledgeKick("k-1", "proxy-1", true, false);

        var request = transport.requests().getFirst();
        assertEquals("POST", request.method());
        assertEquals("/kick/k-1/deliveries", request.uri().getPath());
        assertEquals("Bearer proxy-token", request.headers().firstValue("Authorization").orElse(""));
        assertTrue(transport.bodyOf(0).contains("\"proxyId\":\"proxy-1\""), transport.bodyOf(0));
        assertTrue(transport.bodyOf(0).contains("\"found\":true") && transport.bodyOf(0).contains("\"moved\":false"), transport.bodyOf(0));
    }

    @Test
    void theReasonIsReadFromTheKickRoute() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200,
                "{\"id\":\"k-1\",\"kind\":\"PLAYER\",\"scope\":\"NETWORK\",\"reason\":\"calm down\",\"createdAt\":1,"
                        + "\"status\":\"PENDING\",\"deliveredCount\":0,\"deadlineAt\":2}");

        String reason = gateway(transport).kickStatus("k-1").getReason();

        assertEquals("calm down", reason);
        assertEquals("GET", transport.requests().getFirst().method());
        assertEquals("/kick/k-1", transport.requests().getFirst().uri().getPath());
    }

    @Test
    void aKickCarriesTheIdempotencyKeyHeader() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(202,
                "{\"id\":\"k-1\",\"kind\":\"PLAYER\",\"status\":\"PENDING\",\"deadlineAt\":2,\"warnings\":[],\"repeated\":false}");

        gateway(transport).kick("key-1", "owner-hash", KickScope.NETWORK, null, "calm down");

        var request = transport.requests().getFirst();
        assertEquals("key-1", request.headers().firstValue("Idempotency-Key").orElse(""));
        assertEquals("/kick", request.uri().getPath());
    }
}
