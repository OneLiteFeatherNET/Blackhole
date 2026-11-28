package net.onelitefeather.blackhole.backend.warning;

import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.warning.dto.WarningDTO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The player confirms a warning once; a repeat keeps the first confirmation and answers the same way. */
class WarningAcknowledgementTest {

    private static final String SECTION = "network";
    private static final String OTHER_OWNER = "cd".repeat(64);

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private String givenWarning() {
        bed.givenTemplate("lobby-warn", SECTION, "lobby-warn", new TemplateStep(StepKind.WARN, null, 0));
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "lobby-warn", null, null), ACTOR);
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the warning must be applied").punishment().identifier();
    }

    private PunishmentEntity stored(String id) {
        return bed.punishments.repository().findById(id).orElseThrow();
    }

    private WarningDTO acknowledge(String id, String owner) {
        return bed.warningService.acknowledge(id, new WarningDTO.AcknowledgeRequest(owner));
    }

    @Test
    void aFirstAcknowledgementSetsTheTimeOfTheAcknowledgement() {
        String id = givenWarning();

        WarningDTO result = acknowledge(id, OWNER);

        assertInstanceOf(WarningDTO.Acknowledged.class, result, "the owner's acknowledgement is accepted");
        assertEquals(bed.eloBed.clock.millis(), stored(id).getAcknowledgedAt(), "the time is the injected clock's");
    }

    @Test
    void aRepeatAcknowledgementKeepsTheFirstTimeAndIsStillAccepted() {
        String id = givenWarning();
        acknowledge(id, OWNER);
        long first = stored(id).getAcknowledgedAt();
        bed.eloBed.clock.advance(Duration.ofMinutes(5));

        WarningDTO repeat = acknowledge(id, OWNER);

        assertInstanceOf(WarningDTO.Acknowledged.class, repeat, "a repeat answers like the first acknowledgement");
        assertEquals(first, stored(id).getAcknowledgedAt(), "a repeat keeps the first time");
    }

    @Test
    void aRepeatAcknowledgementRecordsNoSecondCaseEvent() {
        String id = givenWarning();
        acknowledge(id, OWNER);

        acknowledge(id, OWNER);

        assertEquals(1, bed.cases.log.ofType(id, "punishment.acknowledged").size(), "the repeat wrote no second case event");
    }

    @Test
    void theFirstAcknowledgementRecordsOneCaseEventWithItsDedupeKey() {
        String id = givenWarning();

        acknowledge(id, OWNER);

        List<CaseEventRecord> events = bed.cases.log.ofType(id, "punishment.acknowledged");
        assertEquals(1, events.size(), "exactly one acknowledged event");
        assertEquals("acknowledged:" + id, events.getFirst().dedupeKey());
    }

    @Test
    void anotherOwnerGetsNotFoundAndNothingChanges() {
        String id = givenWarning();

        WarningDTO result = acknowledge(id, OTHER_OWNER);

        WarningDTO.Error error = assertInstanceOf(WarningDTO.Error.class, result, "a mismatched owner is refused");
        assertEquals(ErrorKind.NOT_FOUND, error.kind(), "the answer reveals nothing: the same as an unknown id");
        assertNull(stored(id).getAcknowledgedAt(), "the warning stays pending");
        assertEquals(0, bed.cases.log.ofType(id, "punishment.acknowledged").size(), "no case event for a refused acknowledgement");
    }

    @Test
    void anUnknownWarningIsNotFound() {
        WarningDTO result = acknowledge("no-such-warning", OWNER);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(WarningDTO.Error.class, result, "an unknown id is refused").kind());
    }
}
