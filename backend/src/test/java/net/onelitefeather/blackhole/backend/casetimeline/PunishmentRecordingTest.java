package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentExpirer;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.PROXY;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The punishment flows record their lifecycle in the case, after their own write. */
class PunishmentRecordingTest {

    private static final UUID INGAME = UUID.nameUUIDFromBytes("ingame-staff".getBytes());
    private static final UUID LINKED_PERSON = UUID.nameUUIDFromBytes("linked-person".getBytes());

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentEntity apply(String templateKey, UUID onBehalfOf, Actor actor) {
        PunishmentDTO.ApplyResponse applied = assertInstanceOf(PunishmentDTO.ApplyResponse.class,
                bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, templateKey, null, onBehalfOf), actor));
        return stored(applied.punishment().identifier());
    }

    private PunishmentEntity stored(String identifier) {
        return bed.punishments.all().stream().filter(p -> p.getIdentifier().equals(identifier)).findFirst().orElseThrow();
    }

    private CaseEventRecord only(String punishment, String type) {
        List<CaseEventRecord> events = bed.cases.log.ofType(punishment, type);
        assertEquals(1, events.size(), "exactly one " + type + " event expected but found " + events);
        return events.getFirst();
    }

    @Test
    void applyingATemplateRecordsTheStepTheCostAndTheScores() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 80));

        PunishmentEntity punishment = apply("spam", null, ACTOR);

        CaseEventRecord event = only(punishment.getIdentifier(), "punishment.applied");
        assertEquals(new CaseEventPayload.Applied("chat", 0, 1, 80, 1000, 920, false, false,
                        punishment.getTemplate().getIdentifier().toString(), bed.eloBed.clock.millis() + Duration.ofHours(1).toMillis()),
                event.payload(), "section, step, cost, scores and expiry of the application");
        assertEquals("applied:" + punishment.getIdentifier(), event.dedupeKey());
    }

    @Test
    void theAppliedEventIsLiveAndDatedByTheClock() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));

        PunishmentEntity punishment = apply("spam", null, ACTOR);

        CaseEventRecord event = only(punishment.getIdentifier(), "punishment.applied");
        assertEquals(CaseEventOrigin.LIVE, event.origin());
        assertEquals(bed.eloBed.clock.millis(), event.occurredAt(), "from the injected clock");
    }

    @Test
    void aWebApplicationIsVerifiedAndAttributedToThePerson() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));

        CaseEventRecord event = only(apply("spam", null, ACTOR).getIdentifier(), "punishment.applied");

        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(STAFF, event.actorOid());
    }

    @Test
    void anInGameApplicationByALinkedStaffMemberKeepsTheProxyAsRecorder() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        bed.staffLinks.givenLink(LINKED_PERSON, INGAME);

        CaseEventRecord event = only(apply("spam", INGAME, PROXY).getIdentifier(), "punishment.applied");

        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(LINKED_PERSON, event.actorOid(), "the linked person");
        assertEquals(PROXY.oid(), event.recordedByOid(), "the proxy wrote it");
    }

    @Test
    void anInGameApplicationByAnUnlinkedUuidIsUnverified() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));

        CaseEventRecord event = only(apply("spam", INGAME, PROXY).getIdentifier(), "punishment.applied");

        assertEquals(ActorTrust.UNVERIFIED, event.actorTrust());
        assertNull(event.actorOid());
        assertEquals(INGAME, event.actorSource(), "only the attributed uuid is known");
    }

    @Test
    void aSecondOffenceRecordsTheSecondStep() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0), step("PT6H", 0));
        apply("spam", null, ACTOR);

        PunishmentEntity second = apply("spam", null, ACTOR);

        CaseEventPayload.Applied payload = assertInstanceOf(CaseEventPayload.Applied.class,
                only(second.getIdentifier(), "punishment.applied").payload());
        assertEquals(1, payload.stepIndex(), "zero based index of the second step");
        assertEquals(2, payload.stepCount());
    }

    @Test
    void anEscalationToAPermanentBanIsRecordedBySystemAsEscalated() {
        bed.elo.applyDelta(OWNER, "network", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        PunishmentEntity ban = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("network");

        CaseEventRecord event = only(ban.getIdentifier(), "punishment.applied");

        assertEquals(ActorTrust.SYSTEM, event.actorTrust());
        CaseEventPayload.Applied payload = assertInstanceOf(CaseEventPayload.Applied.class, event.payload());
        assertTrue(payload.escalated(), "applied by escalation");
        assertTrue(payload.permanent());
    }

    @Test
    void aSoftThresholdPunishmentIsRecordedBySystem() {
        bed.eloBed.givenScore(OWNER, "chat", 720);
        bed.elo.applyDelta(OWNER, "chat", -40, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        PunishmentEntity soft = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("chat");

        assertEquals(ActorTrust.SYSTEM, only(soft.getIdentifier(), "punishment.applied").actorTrust());
    }

    @Test
    void revokingRecordsWhoRevokedIt() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        PunishmentEntity punishment = apply("grief", null, ACTOR);

        bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "network", null), ACTOR);

        CaseEventRecord event = only(punishment.getIdentifier(), "punishment.revoked");
        assertEquals(new CaseEventPayload.Revoked(false), event.payload());
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(STAFF, event.actorOid());
        assertEquals("revoked:" + punishment.getIdentifier(), event.dedupeKey());
    }

    @Test
    void anExpiredPunishmentIsRecordedOnceBySystemAtItsExpiry() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        PunishmentEntity punishment = apply("spam", null, ACTOR);
        long expiresAt = bed.eloBed.clock.millis() + Duration.ofHours(1).toMillis();
        bed.eloBed.clock.advance(Duration.ofHours(2));
        ActivePunishmentExpirer expirer = new ActivePunishmentExpirer(bed.profiles.repository(), bed.slots, bed.eloBed.sections,
                bed.eloBed.events, bed.profileCache, bed.cases.timeline, bed.eloBed.clock);
        PunishmentProfileEntity profile = bed.profiles.repository().findById(OWNER).orElseThrow();

        expirer.expire(profile);
        bed.cases.timeline.expired(punishment);

        CaseEventRecord event = only(punishment.getIdentifier(), "punishment.expired");
        assertEquals(new CaseEventPayload.Expired(expiresAt), event.payload());
        assertEquals(ActorTrust.SYSTEM, event.actorTrust());
    }

    @Test
    void aFailingRecordingDoesNotFailTheApplication() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 80));
        bed.cases.log.failAppends(new IllegalStateException("database down"));

        PunishmentEntity punishment = apply("spam", null, ACTOR);

        assertTrue(bed.punishments.all().contains(punishment), "the punishment stands");
        assertTrue(bed.profiles.repository().findById(OWNER).orElseThrow().getActive().containsKey("chat"), "and is active");
        assertEquals(1.0, bed.cases.failures(), "the failure is counted");
    }

    @Test
    void aFailingRecordingDoesNotFailTheRevoke() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        apply("grief", null, ACTOR);
        bed.cases.log.failAppends(new IllegalStateException("database down"));

        PunishmentDTO result = bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "network", null), ACTOR);

        assertInstanceOf(PunishmentDTO.RevokeResponse.class, result, "the punishment is revoked regardless");
        assertTrue(bed.profiles.repository().findById(OWNER).orElseThrow().getActive().isEmpty(), "it left the active slot");
    }

    @Test
    void aFailingRecordingDoesNotFailAnAutomaticPunishment() {
        bed.cases.log.failAppends(new IllegalStateException("database down"));

        bed.elo.applyDelta(OWNER, "network", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());

        assertTrue(bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("network").isPermanent(),
                "the escalation ban stands");
    }
}
