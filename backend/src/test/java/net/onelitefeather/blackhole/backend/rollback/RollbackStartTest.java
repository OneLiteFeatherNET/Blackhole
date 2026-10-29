package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackStartTest {

    private final RollbackBed world = new RollbackBed();

    private void givenPreview() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
    }

    @Test
    void aStartWithoutAnIdempotencyKeyIsRefused() {
        givenPreview();

        RollbackDTO result = world.execution().start(RollbackBed.JOB, "compromised account", null);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(RollbackDTO.Error.class, result).kind());
        assertEquals("PREVIEW", world.jobs.find(RollbackBed.JOB).orElseThrow().state(), "the job stays a preview");
    }

    @Test
    void aReasonLongerThanFiveHundredCharactersIsRefused() {
        givenPreview();

        RollbackDTO result = world.execution().start(RollbackBed.JOB, "x".repeat(501), "key-1");

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(RollbackDTO.Error.class, result).kind());
    }

    @Test
    void aRepeatedStartWithTheSameKeyAnswersTheSameJobAndWritesNothing() {
        givenPreview();
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        RollbackDTO repeat = world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        RollbackDTO.Started started = assertInstanceOf(RollbackDTO.Started.class, repeat);
        assertTrue(started.repeated(), "the answer says it is a repeat");
        assertEquals(1, world.bed.eloBed.events.eventsOfType(RollbackEvents.STARTED).size(), "only the first start publishes");
    }

    @Test
    void anExpiredPreviewCannotBeStarted() {
        givenPreview();
        world.jobs.expire(RollbackBed.JOB);
        world.bed.eloBed.clock.advance(Duration.ofHours(2));

        RollbackDTO result = world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        assertEquals(ErrorKind.CONFLICT, assertInstanceOf(RollbackDTO.Error.class, result).kind());
    }
}
