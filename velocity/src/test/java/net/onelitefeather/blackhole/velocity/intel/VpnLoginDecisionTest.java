package net.onelitefeather.blackhole.velocity.intel;

import net.onelitefeather.blackhole.client.model.IntelPolicy;
import net.onelitefeather.blackhole.client.model.LoginCheckResponse;
import net.onelitefeather.blackhole.velocity.punish.StubGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VpnLoginDecisionTest {

    private static final String OWNER = "a".repeat(128);
    private static final String IP = "203.0.113.7";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final StubGateway gateway = new StubGateway();

    private VpnLoginDecision decision() {
        return new VpnLoginDecision(this.gateway, TIMEOUT);
    }

    private void answerWith(IntelPolicy verdict) {
        this.gateway.loginCheckAnswer = CompletableFuture.completedFuture(new LoginCheckResponse().verdict(verdict));
    }

    @Test
    void aDenyVerdictIsReturnedAsDeny() {
        answerWith(IntelPolicy.DENY);

        assertEquals(IntelPolicy.DENY, decision().decide(OWNER, IP), "a DENY verdict must reach the listener");
    }

    @Test
    void aFlagVerdictIsReturnedAsFlag() {
        answerWith(IntelPolicy.FLAG);

        assertEquals(IntelPolicy.FLAG, decision().decide(OWNER, IP), "a FLAG verdict must not deny the login");
    }

    @Test
    void anAllowVerdictIsReturnedAsAllow() {
        answerWith(IntelPolicy.ALLOW);

        assertEquals(IntelPolicy.ALLOW, decision().decide(OWNER, IP), "an ALLOW verdict must not deny the login");
    }

    @Test
    void aTimeoutAllowsTheLoginAndCountsAFailOpen() {
        this.gateway.loginCheckAnswer = new CompletableFuture<>();
        VpnLoginDecision decision = new VpnLoginDecision(this.gateway, Duration.ofMillis(1));

        assertEquals(IntelPolicy.ALLOW, decision.decide(OWNER, IP), "a login must not wait for a backend that does not answer");
        assertEquals(1, decision.failOpenCount(), "the timeout is counted as a fail-open event");
    }

    @Test
    void anErrorAllowsTheLoginAndCountsAFailOpen() {
        this.gateway.loginCheckAnswer = CompletableFuture.failedFuture(new IllegalStateException("backend error"));
        VpnLoginDecision decision = decision();

        assertEquals(IntelPolicy.ALLOW, decision.decide(OWNER, IP), "a broken backend must not lock players out");
        assertEquals(1, decision.failOpenCount(), "the error is counted as a fail-open event");
    }

    @Test
    void anUnknownVerdictAllowsTheLoginAndCountsAFailOpen() {
        answerWith(null);
        VpnLoginDecision decision = decision();

        assertEquals(IntelPolicy.ALLOW, decision.decide(OWNER, IP), "an unknown verdict must not deny the login");
        assertEquals(1, decision.failOpenCount(), "the unknown verdict is counted as a fail-open event");
    }

    @Test
    void aGivenAnswerDoesNotCountAsAFailOpen() {
        answerWith(IntelPolicy.FLAG);
        VpnLoginDecision decision = decision();

        decision.decide(OWNER, IP);

        assertEquals(0, decision.failOpenCount(), "only a missing answer is a fail-open event");
    }

    @Test
    void theOwnerAndTheAddressAreSentToTheBackend() {
        answerWith(IntelPolicy.ALLOW);

        decision().decide(OWNER, IP);

        assertEquals(new StubGateway.LoginCheck(OWNER, IP), this.gateway.loginChecks.getFirst(), "the check names the owner and the address");
    }
}
