package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentExpirer;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing the active slot: a failed placement must not leave a punishment behind that counts for
 * escalation, and clearing an expired slot must not remove a newer punishment.
 */
class PunishmentPlacementTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final ActivePunishmentExpirer expirer = new ActivePunishmentExpirer(
            bed.profiles.repository(), bed.slots, bed.eloBed.sections, bed.eloBed.events, bed.profileCache, bed.cases.timeline, bed.eloBed.clock);

    private PunishmentDTO.ApplyResponse apply(String key) {
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class,
                bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, key, null, null), ACTOR));
    }

    @Test
    void aPunishmentWhoseSlotWriteFailsIsNotKeptAsAnOrphan() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0), step("PT2H", 0));
        bed.slots.failNextOccupy(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class,
                () -> bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR));

        assertTrue(bed.punishments.all().isEmpty(), "the saved punishment must be removed again");
    }

    @Test
    void anOrphanedPunishmentDoesNotAdvanceTheEscalationStepOfTheNextApply() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0), step("PT2H", 0));
        bed.slots.failNextOccupy(new IllegalStateException("database down"));
        assertThrows(IllegalStateException.class,
                () -> bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR));

        PunishmentDTO.ApplyResponse retry = apply("spam");

        assertEquals(1, retry.step(), "the failed attempt must not count as an earlier offence");
    }

    @Test
    void expiringAStaleCopyDoesNotRemoveAPunishmentAppliedMeanwhile() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0), step("PT1H", 0));
        apply("spam");
        PunishmentEntity first = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("chat");
        PunishmentProfileEntity stale = new PunishmentProfileEntity(OWNER, new HashMap<>(
                bed.profiles.repository().findById(OWNER).orElseThrow().getActive()), new ArrayList<>(), new HashMap<>());
        bed.eloBed.clock.advance(Duration.ofHours(2));
        PunishmentDTO.ApplyResponse second = apply("spam");
        bed.eloBed.events.events().clear();

        boolean changed = expirer.expire(stale);

        PunishmentProfileEntity stored = bed.profiles.repository().findById(OWNER).orElseThrow();
        assertFalse(changed, "the stale slot no longer holds the expired punishment, so nothing expired");
        assertEquals(second.punishment().identifier(), stored.getActive().get("chat").getIdentifier(),
                "the newer mute must stay active");
        assertTrue(bed.eloBed.events.eventsOfType("punishment.expired").isEmpty(), "no expiry may be announced for the replaced mute");
        assertEquals(first.getIdentifier(), stored.getHistory().getFirst().getIdentifier(), "the first mute was moved to history by the apply");
    }
}
