package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentRevokeTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentDTO revoke(String section) {
        return bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, section, null), ACTOR);
    }

    private void givenActiveMute() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
    }

    @Test
    void revokingATemporaryPunishmentMovesItToHistoryAndRecordsWhoRevokedIt() {
        givenActiveMute();

        PunishmentDTO.RevokeResponse result = assertInstanceOf(PunishmentDTO.RevokeResponse.class, revoke("chat"));

        var profile = bed.profiles.repository().findById(OWNER).orElseThrow();
        assertFalse(profile.getActive().containsKey("chat"), "the chat slot is cleared");
        assertEquals(1, profile.getHistory().size(), "moved to history");
        assertEquals(STAFF.toString(), profile.getHistory().getFirst().getMetaData().get("revokedBy"));
        assertTrue(profile.getHistory().getFirst().isLifted(), "marked lifted so escalation ignores it");
        assertNull(result.scoreReset(), "a temporary punishment does not reset the score");
        assertEquals(950, bed.eloBed.storedScore(OWNER, "chat"), "the score is not given back by a plain revoke");
    }

    @Test
    void revokingPublishesTheRevocationWithTheSectionAndEnforcement() {
        givenActiveMute();

        revoke("chat");

        var event = bed.eloBed.events.eventsOfType("punishment.revoked").getFirst();
        assertEquals("chat", event.payload().get("section"));
        assertEquals("MUTE", event.payload().get("enforcement"));
        assertEquals(STAFF.toString(), event.payload().get("revokedBy"));
        assertEquals(OWNER, event.payload().get("owner"));
    }

    @Test
    void revokingEvictsTheCachedProfile() {
        givenActiveMute();
        bed.profileCache.put(OWNER, new PunishProfileDTO(OWNER, Map.of(), List.of(), Map.of()));

        revoke("chat");

        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.profile(OWNER)));
    }

    @Test
    void revokingAPermanentPunishmentResetsTheScoreToThePardonScore() {
        bed.elo.applyDelta(OWNER, "network", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        assertEquals(200, bed.eloBed.storedScore(OWNER, "network"), "precondition: escalated");

        PunishmentDTO.RevokeResponse result = assertInstanceOf(PunishmentDTO.RevokeResponse.class, revoke("network"));

        assertEquals(700, result.scoreReset(), "pardon score of the network section");
        assertEquals(700, bed.eloBed.storedScore(OWNER, "network"));
        assertTrue(bed.eloBed.eventRepository.all().stream().anyMatch(e -> e.getReasonCode() == EloReasonCode.PARDON_RESET),
                "the reset is part of the audit trail");
    }

    @Test
    void revokingInASectionWithoutAnActivePunishmentIsNotFound() {
        givenActiveMute();

        PunishmentDTO.Error error = assertInstanceOf(PunishmentDTO.Error.class, revoke("network"));

        assertEquals(ErrorKind.NOT_FOUND, error.kind());
        assertTrue(bed.eloBed.events.eventsOfType("punishment.revoked").isEmpty(), "nothing published");
    }

    @Test
    void revokingForAnOwnerWithoutAProfileIsNotFound() {
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(PunishmentDTO.Error.class, revoke("chat")).kind());
    }

    @Test
    void revokingOneSectionLeavesTheOthersActive() {
        givenActiveMute();
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "grief", null, null), ACTOR);

        revoke("chat");

        var profile = bed.profiles.repository().findById(OWNER).orElseThrow();
        assertTrue(profile.getActive().containsKey("network"), "the ban stays active");
    }

    @Test
    void revokingMarksTheEvidenceOfThePunishmentRevoked() {
        givenActiveMute();
        String identifier = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("chat").getIdentifier();

        revoke("chat");

        assertEquals(List.of(identifier), bed.evidence.revoked(), "the revoked punishment's evidence is marked");
    }

    @Test
    void aFailingEvidenceMarkDoesNotFailTheRevoke() {
        givenActiveMute();
        bed.evidence.failClaims(new IllegalStateException("database down"));

        assertInstanceOf(PunishmentDTO.RevokeResponse.class, revoke("chat"), "the punishment is revoked regardless");
    }
}
