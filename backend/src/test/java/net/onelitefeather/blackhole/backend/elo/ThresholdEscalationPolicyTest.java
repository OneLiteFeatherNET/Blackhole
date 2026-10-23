package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.elo.EscalationPolicy.Context;
import net.onelitefeather.blackhole.backend.elo.EscalationPolicy.Decision;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThresholdEscalationPolicyTest {

    private final ThresholdEscalationPolicy policy = new ThresholdEscalationPolicy();

    /** base 1000, soft 700, permanent 300. */
    private static final SectionDTO.Response WITH_SOFT = new SectionDTO.Response(
            "chat", "Chat", EnforcementKind.MUTE, null, List.of(), 1000, 700, "PT24H", 300, 700, "muted", 10, true);

    private static final SectionDTO.Response WITHOUT_SOFT = new SectionDTO.Response(
            "chat", "Chat", EnforcementKind.MUTE, null, List.of(), 1000, null, null, 300, 700, "muted", 10, true);

    private Decision decide(SectionDTO.Response section, int before, int after, boolean hasActivePermanent) {
        return policy.decide(new Context("owner", section, before, after, hasActivePermanent));
    }

    @Test
    void crossingTheSoftThresholdDownwardAppliesASoftPunishment() {
        assertEquals(Decision.SOFT, decide(WITH_SOFT, 720, 680, false));
    }

    @Test
    void landingExactlyOnTheSoftThresholdDoesNotCross() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 720, 700, false), "the threshold itself is still fine");
    }

    @Test
    void startingExactlyOnTheSoftThresholdAndDroppingBelowCrosses() {
        assertEquals(Decision.SOFT, decide(WITH_SOFT, 700, 699, false));
    }

    @Test
    void aFurtherDeductionWhileAlreadyBelowTheSoftThresholdDoesNothing() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 650, 600, false), "no repeated soft punishment");
    }

    @Test
    void aSectionWithoutASoftThresholdNeverSoftPunishes() {
        assertEquals(Decision.NONE, decide(WITHOUT_SOFT, 900, 400, false));
    }

    @Test
    void fallingBelowThePermanentThresholdEscalatesPermanently() {
        assertEquals(Decision.PERMANENT, decide(WITH_SOFT, 380, 230, false));
    }

    @Test
    void landingExactlyOnThePermanentThresholdDoesNotEscalate() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 380, 300, false), "below means strictly below");
    }

    @Test
    void crossingBothThresholdsAtOnceEscalatesPermanentlyOnly() {
        assertEquals(Decision.PERMANENT, decide(WITH_SOFT, 750, 250, false), "no additional soft punishment for the same change");
    }

    @Test
    void anAlreadyPermanentlyPunishedPlayerIsNotEscalatedAgain() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 250, 200, true));
    }

    @Test
    void aSoftCrossingNeverReplacesAnActivePermanentPunishment() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 720, 680, true));
    }

    @Test
    void aPardonedPlayerStillBelowThePermanentThresholdIsEscalatedAgainOnTheNextDeduction() {
        assertEquals(Decision.PERMANENT, decide(WITH_SOFT, 250, 200, false), "the rule is state based, not crossing based");
    }

    @Test
    void anIncreaseNeverEscalates() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 200, 250, false), "rewards never punish");
    }

    @Test
    void aZeroDeltaNeverEscalates() {
        assertEquals(Decision.NONE, decide(WITH_SOFT, 200, 200, false));
    }
}
