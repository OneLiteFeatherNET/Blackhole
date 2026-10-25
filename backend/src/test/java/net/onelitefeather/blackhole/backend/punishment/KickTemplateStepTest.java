package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A template step of kind KICK kicks, costs its ELO once and counts for escalation. */
class KickTemplateStepTest {

    private static final String SECTION = "network";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @BeforeEach
    void givenAChatSpamLadderOfAKickThenADayBan() {
        bed.givenTemplate("chat-spam", SECTION, "chat-spam", new TemplateStep(StepKind.KICK, null, 10), step("P1D", 50));
    }

    private PunishmentDTO apply(KickScope scope, String target) {
        return bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "chat-spam", null, null, null, scope, target), ACTOR);
    }

    private PunishmentDTO.ApplyResponse applied(KickScope scope, String target) {
        PunishmentDTO result = apply(scope, target);
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "expected the template to be applied but got " + result);
    }

    private PunishmentEntity stored(PunishmentDTO.ApplyResponse response) {
        return bed.punishments.repository().findById(response.punishment().identifier()).orElseThrow();
    }

    @Test
    void aKickStepCreatesAKickRow() {
        PunishmentDTO.ApplyResponse response = applied(null, null);

        PunishmentEntity row = stored(response);
        assertEquals(PunishmentKind.KICK, row.getKind(), "the first step is a kick");
        assertEquals("chat-spam", row.getEscalationGroup());
        assertEquals(0, row.getStepIndex());
        assertEquals(2, row.getStepCount());
        assertEquals(10, row.getEloCost());
        assertFalse(row.isPermanent(), "a kick is never permanent");
    }

    @Test
    void aKickStepNeverBecomesActive() {
        applied(null, null);

        assertTrue(bed.profiles.repository().findById(OWNER).map(profile -> profile.getActive().isEmpty()).orElse(true),
                "the kick must not occupy the section slot");
    }

    @Test
    void aKickStepWithoutAScopeKicksFromTheNetwork() {
        PunishmentEntity row = stored(applied(null, null));

        assertEquals(KickScope.NETWORK.name(), row.getKickScope());
        assertNull(row.getKickTarget(), "a network kick has no target");
    }

    @Test
    void aKickStepKicksFromTheServerTheRequestNames() {
        PunishmentEntity row = stored(applied(KickScope.SERVER, "Lobby-1"));

        assertEquals(KickScope.SERVER.name(), row.getKickScope());
        assertEquals("Lobby-1", row.getKickTarget());
    }

    @Test
    void aServerKickStepWithoutATargetIsRefusedAndWritesNothing() {
        PunishmentDTO result = apply(KickScope.SERVER, null);

        PunishmentDTO.Error error = assertInstanceOf(PunishmentDTO.Error.class, result);
        assertEquals(ErrorKind.KICK_TARGET_INVALID, error.kind(), error.errorMessage());
        assertTrue(bed.punishments.all().isEmpty(), "nothing is written for a refused kick");
        assertEquals(-1, bed.eloBed.storedScore(OWNER, SECTION), "and no ELO is charged");
    }

    @Test
    void aKickStepChangesTheScoreByItsCostOnce() {
        int base = bed.elo.score(OWNER, SECTION).orElseThrow();

        PunishmentDTO.ApplyResponse response = applied(null, null);

        assertEquals(base, response.scoreBefore());
        assertEquals(base - 10, response.scoreAfter(), "the cost of the kick step is deducted");
        assertEquals(base - 10, bed.eloBed.storedScore(OWNER, SECTION), "deducted exactly once");
    }

    @Test
    void aSecondApplicationWithinTheWindowSelectsTheBanStep() {
        applied(null, null);
        bed.eloBed.clock.advance(Duration.ofHours(1));

        PunishmentDTO.ApplyResponse second = applied(null, null);

        assertEquals(2, second.step(), "the kick counted as the first offence");
        assertEquals(PunishmentKind.PUNISHMENT, stored(second).getKind(), "the second step is the one-day ban");
        assertEquals(bed.eloBed.clock.millis() + Duration.ofDays(1).toMillis(), second.punishment().metaData().get("expiration_date"));
    }

    @Test
    void theKickDoesNotExpireTheEscalationWindow() {
        applied(null, null);
        bed.eloBed.clock.advance(Duration.ofDays(200));

        assertEquals(2, applied(null, null).step(), "a kick 200 days ago is still inside the 365 day window");
    }

    @Test
    void aKickStepIsAcceptedForAPlayerWhoIsPermanentlyBannedInTheSection() {
        PunishmentEntity permanent = bed.punishments.repository().save(
                new PunishmentEntity("perm-1", PunishmentTestBed.STAFF, OWNER, SECTION, true, bed.eloBed.clock.millis(), null, new HashMap<>()));
        bed.slots.occupy(OWNER, SECTION, permanent.getIdentifier());

        PunishmentDTO result = apply(null, null);

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "a kick does not conflict with a ban: " + result);
    }
}
