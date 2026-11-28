package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning calls of the gateway: the warning-only flag of an apply, and the acknowledgement route. */
class HttpBackendGatewayWarningTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");

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
        return new HttpBackendGateway(BlackholeApiClients.create("http://backend.test", TOKEN, transport), Clock.systemUTC());
    }

    @Test
    void aWarnApplySendsTheWarningOnlyFlag() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).apply("owner-hash", null, "spam", STAFF, null, true);

        assertTrue(transport.bodyOf(0).contains("\"warningOnly\":true"), "the backend refuses a non-warning step: " + transport.bodyOf(0));
    }

    @Test
    void aPunishApplyDoesNotSendTheWarningOnlyFlag() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).apply("owner-hash", null, "spam", STAFF, null, false);

        assertFalse(transport.bodyOf(0).contains("warningOnly"), "an ordinary apply is sent as before: " + transport.bodyOf(0));
    }

    @Test
    void theAcknowledgementPostsTheOwnerToTheWarningRoute() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(204, "");

        gateway(transport).acknowledgeWarning("7K3M9QXA", "owner-hash");

        assertEquals("POST", transport.requests().getFirst().method());
        assertTrue(transport.requests().getFirst().uri().getPath().endsWith("/warn/7K3M9QXA/acknowledge"),
                transport.requests().getFirst().uri().toString());
        assertTrue(transport.bodyOf(0).contains("\"owner\":\"owner-hash\""), transport.bodyOf(0));
    }
}
