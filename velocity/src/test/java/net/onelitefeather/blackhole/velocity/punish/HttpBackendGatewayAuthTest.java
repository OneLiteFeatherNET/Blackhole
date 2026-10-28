package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the backend sees of a staff command: the proxy's bearer token and the staff member's UUID as
 * {@code onBehalfOf}.
 */
class HttpBackendGatewayAuthTest {

    private static final UUID STAFF = UUID.fromString("11111111-2222-4333-8444-555555555555");

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
        return new HttpBackendGateway(client, Clock.systemUTC());
    }

    @Test
    void applyingAPunishmentSendsTheBearerTokenAndTheStaffMemberAsOnBehalfOf() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).apply("owner-hash", "spam", STAFF, null, false);

        assertEquals("Bearer proxy-token", transport.requests().getFirst().headers().firstValue("Authorization").orElse(""),
                "the proxy authenticates as itself");
        assertTrue(transport.bodyOf(0).contains("\"onBehalfOf\":\"" + STAFF + "\""),
                "the staff member is the delegated actor: " + transport.bodyOf(0));
    }

    @Test
    void revokingAPunishmentSendsTheBearerTokenAndTheStaffMemberAsOnBehalfOf() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).revoke("owner-hash", "chat", STAFF);

        assertEquals("Bearer proxy-token", transport.requests().getFirst().headers().firstValue("Authorization").orElse(""),
                "the proxy authenticates as itself");
        assertTrue(transport.bodyOf(0).contains("\"onBehalfOf\":\"" + STAFF + "\""),
                "the staff member is the delegated actor: " + transport.bodyOf(0));
    }

    @Test
    void evidenceIsPostedOnBehalfOfTheStaffMember() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).postChatEvidence("owner-hash", "corr-1",
                new ChatSnapshot(java.util.List.of(new ChatSnapshot.Entry(1L, "Spieler A", "hi"))), "velocity", STAFF).get();

        assertTrue(transport.bodyOf(0).contains("\"onBehalfOf\":\"" + STAFF + "\""),
                "the staff member is the delegated actor: " + transport.bodyOf(0));
    }
}
