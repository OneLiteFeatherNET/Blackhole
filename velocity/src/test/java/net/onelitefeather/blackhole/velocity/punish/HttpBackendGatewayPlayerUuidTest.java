package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The apply call carries the player's raw UUID next to the owner hash, and omits it when unknown. */
class HttpBackendGatewayPlayerUuidTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

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
    void anApplySendsThePlayerUuidNextToTheOwner() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).apply("owner-hash", PLAYER, "spam", STAFF, null, false);

        assertTrue(transport.bodyOf(0).contains("\"playerUuid\":\"123e4567-e89b-12d3-a456-426614174000\""),
                "the public ban list needs the raw UUID: " + transport.bodyOf(0));
        assertTrue(transport.bodyOf(0).contains("\"owner\":\"owner-hash\""), transport.bodyOf(0));
    }

    @Test
    void anApplyWithoutAKnownUuidOmitsThePlayerUuid() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport).apply("owner-hash", null, "spam", STAFF, null, false);

        assertFalse(transport.bodyOf(0).contains("playerUuid"), "an unknown UUID is omitted, not sent as null: " + transport.bodyOf(0));
    }
}
