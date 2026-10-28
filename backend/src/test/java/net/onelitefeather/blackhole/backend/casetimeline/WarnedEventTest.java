package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A warning is recorded once in its own case, with its template key and ELO cost; a failed recording never fails it. */
class WarnedEventTest {

    private static final String TEMPLATE = "lobby-warn";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private String applyWarning(int eloCost) {
        bed.givenTemplate(TEMPLATE, "network", TEMPLATE, new TemplateStep(StepKind.WARN, null, eloCost));
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, TEMPLATE, null, null), ACTOR);
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the warning must be applied").punishment().identifier();
    }

    @Test
    void aWarningRecordsOneWarnedEventWithTemplateKeyAndCost() {
        String id = applyWarning(0);

        List<CaseEventRecord> events = bed.cases.log.ofType(id, "punishment.warned");

        assertEquals(1, events.size(), "exactly one warned event");
        assertEquals(new CaseEventPayload.Warned(TEMPLATE, 0), events.getFirst().payload());
        assertEquals("warned:" + id, events.getFirst().dedupeKey());
    }

    @Test
    void theWarnedEventIsLiveVerifiedAndAttributedToTheStaffMember() {
        String id = applyWarning(0);

        CaseEventRecord event = bed.cases.log.ofType(id, "punishment.warned").getFirst();

        assertEquals(CaseEventOrigin.LIVE, event.origin());
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(STAFF, event.actorOid());
    }

    @Test
    void aWarningIsNeverRecordedAsApplied() {
        String id = applyWarning(0);

        assertEquals(0, bed.cases.log.ofType(id, "punishment.applied").size(), "a warning is no applied punishment");
    }

    @Test
    void aFailedRecordingDoesNotFailTheWarning() {
        bed.givenTemplate(TEMPLATE, "network", TEMPLATE, new TemplateStep(StepKind.WARN, null, 0));
        bed.cases.log.failAppends(new IllegalStateException("database down"));

        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, TEMPLATE, null, null), ACTOR);

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the warning stands even when its case event cannot be stored");
        assertEquals(1.0, bed.cases.failures(), "the failed recording is counted");
    }

    @Test
    void aStoredWarnedPayloadIsReadBackAsTyped() {
        Map<String, Object> body = Map.of("templateKey", TEMPLATE, "eloCost", 5);

        assertEquals(new CaseEventPayload.Warned(TEMPLATE, 5), CaseEventPayloads.read("punishment.warned", 1, body));
    }
}
