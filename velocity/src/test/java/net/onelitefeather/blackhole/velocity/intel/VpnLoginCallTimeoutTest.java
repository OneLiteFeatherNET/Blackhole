package net.onelitefeather.blackhole.velocity.intel;

import net.onelitefeather.blackhole.client.model.IntelPolicy;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.punish.StubGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The login check is bounded by the configured timeout; a backend that never answers is abandoned at it. */
class VpnLoginCallTimeoutTest {

    private static final String OWNER = "b".repeat(128);
    private static final String IP = "198.51.100.4";

    @Test
    void theTimeoutIsTheConfiguredLoginCheckTimeout() {
        VpnLoginDecision decision = new VpnLoginDecision(new StubGateway(), new BlackholeConfig());

        assertEquals(Duration.ofSeconds(1), decision.timeout(), "the default login-check timeout is PT1S");
    }

    @Test
    void aBackendThatNeverAnswersIsAbandonedAtTheTimeoutAndTheLoginIsAllowed() {
        StubGateway gateway = new StubGateway();
        gateway.loginCheckAnswer = new CompletableFuture<>();
        VpnLoginDecision decision = new VpnLoginDecision(gateway, Duration.ofMillis(1));

        assertEquals(IntelPolicy.ALLOW, decision.decide(OWNER, IP), "the login is allowed once the timeout has passed");
        assertEquals(1, decision.failOpenCount(), "the abandoned check is one fail-open event");
        assertEquals(1, gateway.loginChecks.size(), "the check was made once, not retried");
    }
}
