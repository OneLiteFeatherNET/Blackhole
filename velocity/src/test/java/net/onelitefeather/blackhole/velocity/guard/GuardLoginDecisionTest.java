package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.model.IntelPolicy;
import net.onelitefeather.blackhole.client.model.LoginCheckResponse;
import net.onelitefeather.blackhole.client.model.LoginDenialReason;
import net.onelitefeather.blackhole.velocity.intel.VpnLoginDecision;
import net.onelitefeather.blackhole.velocity.punish.StubGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardLoginDecisionTest {

    private static final String OWNER = "a".repeat(128);
    private static final String IP = "203.0.113.7";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final StubGateway gateway = new StubGateway();
    private final GuardLevelCache levels = new GuardLevelCache();

    private GuardLoginDecision decision() {
        return new GuardLoginDecision(new VpnLoginDecision(this.gateway, TIMEOUT), this.levels);
    }

    private void answerWith(IntelPolicy verdict, LoginDenialReason reason) {
        this.gateway.loginCheckAnswer = CompletableFuture.completedFuture(
                new LoginCheckResponse().verdict(verdict).denialReason(reason));
    }

    private void backendSilent() {
        this.gateway.loginCheckAnswer = new CompletableFuture<>();
    }

    private GuardLoginDecision silentBackendDecision() {
        backendSilent();
        return new GuardLoginDecision(new VpnLoginDecision(this.gateway, Duration.ofMillis(1)), this.levels);
    }

    @Test
    void anAreaBanDenialIsRefusedWithItsReason() {
        answerWith(IntelPolicy.DENY, LoginDenialReason.AREA_BAN);

        GuardLoginDecision.Outcome outcome = decision().decide(OWNER, IP, false);

        assertTrue(outcome.refused(), "an area ban refuses the login");
        assertEquals(LoginDenialReason.AREA_BAN, outcome.reason(), "the reason tells the player which screen to show");
    }

    @Test
    void aStrictDenialIsRefusedWithItsReason() {
        answerWith(IntelPolicy.DENY, LoginDenialReason.GUARD_STRICT);

        GuardLoginDecision.Outcome outcome = decision().decide(OWNER, IP, false);

        assertTrue(outcome.refused(), "a strict refusal refuses the login");
        assertEquals(LoginDenialReason.GUARD_STRICT, outcome.reason(), "the strict reason is passed on");
    }

    @Test
    void aLockdownDenialIsRefusedWithItsReason() {
        answerWith(IntelPolicy.DENY, LoginDenialReason.GUARD_LOCKDOWN);

        GuardLoginDecision.Outcome outcome = decision().decide(OWNER, IP, false);

        assertTrue(outcome.refused(), "a lockdown refusal refuses the login");
        assertEquals(LoginDenialReason.GUARD_LOCKDOWN, outcome.reason(), "the lockdown reason is passed on");
    }

    @Test
    void aDenialWithoutAReasonIsAVpnPolicyRefusal() {
        answerWith(IntelPolicy.DENY, null);

        GuardLoginDecision.Outcome outcome = decision().decide(OWNER, IP, false);

        assertTrue(outcome.refused(), "a DENY without a reason still refuses");
        assertNull(outcome.reason(), "no reason means the VPN policy screen");
    }

    @Test
    void anAllowOrFlagVerdictIsNotRefused() {
        answerWith(IntelPolicy.FLAG, null);

        assertFalse(decision().decide(OWNER, IP, false).refused(), "a FLAG verdict must not refuse the login");
    }

    @Test
    void aBackendAnswerIsTrustedOverTheCachedLevel() {
        this.levels.update(GuardLevel.LOCKDOWN);
        answerWith(IntelPolicy.ALLOW, null);

        assertFalse(decision().decide(OWNER, IP, false).refused(), "a backend that allowed the login is trusted");
    }

    @Test
    void timeoutAtStrictAllowsTheLoginAndCountsAFailOpen() {
        this.levels.update(GuardLevel.STRICT);
        GuardLoginDecision decision = silentBackendDecision();

        assertFalse(decision.decide(OWNER, IP, false).refused(), "strict must not lock everybody out during an outage");
        assertEquals(1, decision.failOpenCount(), "the allowed login is counted as a fail-open");
    }

    @Test
    void timeoutAtOffAllowsTheLoginAndCountsAFailOpen() {
        GuardLoginDecision decision = silentBackendDecision();

        assertFalse(decision.decide(OWNER, IP, false).refused(), "off allows the login during an outage");
        assertEquals(1, decision.failOpenCount(), "the allowed login is counted as a fail-open");
    }

    @Test
    void timeoutAtLockdownRefusesAPlayerWithoutTheStaffNode() {
        this.levels.update(GuardLevel.LOCKDOWN);
        GuardLoginDecision decision = silentBackendDecision();

        GuardLoginDecision.Outcome outcome = decision.decide(OWNER, IP, false);

        assertTrue(outcome.refused(), "lockdown refuses the unknown during an outage");
        assertEquals(LoginDenialReason.GUARD_LOCKDOWN, outcome.reason(), "the refusal uses the lockdown screen");
        assertEquals(1, decision.failClosedCount(), "the refusal is counted as a fail-closed");
    }

    @Test
    void timeoutAtLockdownAllowsAPlayerWithTheStaffNode() {
        this.levels.update(GuardLevel.LOCKDOWN);
        GuardLoginDecision decision = silentBackendDecision();

        assertFalse(decision.decide(OWNER, IP, true).refused(), "staff get in during an outage at lockdown");
        assertEquals(0, decision.failClosedCount(), "a staff login is not a fail-closed refusal");
    }

    @Test
    void aFreshProxyWithoutAnyLevelAllowsTheLoginDuringAnOutage() {
        GuardLoginDecision decision = silentBackendDecision();

        assertFalse(decision.decide(OWNER, IP, false).refused(), "a proxy that never heard a level starts open");
    }
}
