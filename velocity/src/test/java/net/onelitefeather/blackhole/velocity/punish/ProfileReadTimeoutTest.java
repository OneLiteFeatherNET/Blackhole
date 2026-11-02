package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A profile read carries its timeout to the request, and a read that is cut off tells nothing: it is unavailable. */
class ProfileReadTimeoutTest {

    private static final String OWNER = "a".repeat(128);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);

    private static final TokenSource TOKEN = new TokenSource() {
        @Override
        public String token() {
            return "proxy-token";
        }

        @Override
        public void rejected(String token) {
        }
    };

    private static HttpBackendGateway gateway(FakeHttpClient transport, Duration timeout) {
        ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport).setReadTimeout(timeout);
        return new HttpBackendGateway(client, CLOCK);
    }

    @Test
    void aProfileReadCarriesTheConfiguredTimeoutToItsRequest() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        gateway(transport, Duration.ofMillis(1500)).profile(OWNER);

        assertEquals(Optional.of(Duration.ofMillis(1500)), transport.requests().getFirst().timeout(),
                "the request gives up after the configured profile timeout");
    }

    @Test
    void aProfileReadThatNeverAnswersIsUnavailableOnceTheTimeoutHasPassed() {
        FakeHttpClient transport = new FakeHttpClient().neverAnswers();
        HttpBackendGateway backend = gateway(transport, Duration.ofSeconds(1));
        PunishmentState state = new PunishmentState(TestData.standardRegistry(), new PunishmentMirror(CLOCK),
                backend::profile, CLOCK, () -> true, Duration.ofMinutes(1));

        assertThrows(PunishmentState.Unavailable.class, () -> state.activeMute(OWNER),
                "a read cut off by the timeout does not say whether the player is muted");
    }
}
