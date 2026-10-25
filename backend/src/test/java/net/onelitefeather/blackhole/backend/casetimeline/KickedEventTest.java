package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.support.KickTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A kick is recorded once in its own case, as a moment, never as an applied punishment. */
class KickedEventTest {

    private final KickTestBed kicks = new KickTestBed();

    private String kickedIdentifier(KickDTO result) {
        KickDTO.Accepted accepted = assertInstanceOf(KickDTO.Accepted.class, result, "the kick must be accepted");
        return net.onelitefeather.blackhole.backend.utils.IdGenerator.generateId(UUID.fromString(accepted.id()));
    }

    @Test
    void aKickRecordsOneKickedEventWithScopeAndTarget() {
        KickDTO result = kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, KickScope.SERVER, "Lobby-1", "AFK farming"), "k-1", ACTOR);

        String id = kickedIdentifier(result);

        List<CaseEventRecord> events = kicks.bed.cases.log.ofType(id, "punishment.kicked");
        assertEquals(1, events.size(), "exactly one kicked event");
        assertEquals(new CaseEventPayload.Kicked("SERVER", "Lobby-1", null), events.getFirst().payload());
        assertEquals("kicked:" + id, events.getFirst().dedupeKey());
    }

    @Test
    void theKickedEventIsLiveVerifiedAndAttributedToTheStaffMember() {
        String id = kickedIdentifier(kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, "x"), "k-2", ACTOR));

        CaseEventRecord event = kicks.bed.cases.log.ofType(id, "punishment.kicked").getFirst();

        assertEquals(CaseEventOrigin.LIVE, event.origin());
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(STAFF, event.actorOid());
        assertEquals(kicks.bed.eloBed.clock.millis(), event.occurredAt(), "dated by the injected clock");
    }

    @Test
    void aKickIsNeverRecordedAsApplied() {
        String id = kickedIdentifier(kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, "x"), "k-3", ACTOR));

        assertTrue(kicks.bed.cases.log.ofType(id, "punishment.applied").isEmpty(), "a kick is no applied punishment");
    }

    @Test
    void aRepeatedKickRecordsNothingMore() {
        KickDTO.CreateRequest request = new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, "x");
        String id = kickedIdentifier(kicks.service.kick(request, "k-4", ACTOR));

        kicks.service.kick(request, "k-4", ACTOR);

        assertEquals(1, kicks.bed.cases.log.ofType(id, "punishment.kicked").size(), "the repeat wrote no second event");
    }

    @Test
    void theEventDoesNotCarryTheReason() {
        String id = kickedIdentifier(kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, "very secret words"), "k-5", ACTOR));

        Map<String, Object> stored = kicks.bed.cases.log.ofType(id, "punishment.kicked").getFirst().payload().toMap();

        assertTrue(!stored.toString().contains("secret"), "the case event holds no reason text: " + stored);
    }

    @Test
    void aStoredKickedPayloadIsReadBackAsTyped() {
        CaseEventPayload.Kicked payload = new CaseEventPayload.Kicked("SECTION", "chat", 10);

        CaseEventPayload read = CaseEventPayloads.read(payload.type(), payload.version(), payload.toMap());

        assertEquals(payload, read);
    }

    @Test
    void theReconcilerRepairsALostKickedEventFromTheKickRow() {
        var kick = new SourceRows.KickRow("kick-lost", STAFF, STAFF, null, "NETWORK", null, null, kicks.bed.eloBed.clock.millis() - 1000);
        var gaps = new CaseReconcilerTest.GapReader();
        gaps.kicked.add(kick);
        var reconciler = new CaseReconciler(gaps, kicks.bed.cases.timeline, kicks.bed.eloBed.clock, java.time.Duration.ofDays(1), java.time.Duration.ZERO);

        int repaired = reconciler.reconcile();

        assertEquals(1, repaired);
        CaseEventRecord event = kicks.bed.cases.log.ofType("kick-lost", "punishment.kicked").getFirst();
        assertEquals(CaseEventOrigin.RECONSTRUCTED, event.origin());
        assertEquals("kicked:kick-lost", event.dedupeKey(), "the same key as the live event, so a replay inserts nothing twice");
    }
}
