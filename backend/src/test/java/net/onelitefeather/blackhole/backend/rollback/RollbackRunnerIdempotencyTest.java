package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackRunnerIdempotencyTest {

    private final RollbackBed world = new RollbackBed();

    @Test
    void runningTheRunnerTwiceRevokesEachFrozenItemOnce() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");
        RollbackRunner runner = world.runner();

        assertTrue(runner.tick(), "the first tick claims the job and revokes its item");
        assertFalse(runner.tick(), "the job is complete, so a second tick has nothing to claim");

        assertEquals(1, world.bed.eloBed.events.eventsOfType("punishment.revoked").size(), "one revoke, one event");
        assertEquals("REVOKED", world.jobs.stateOf(RollbackBed.JOB, identifier));
    }

    @Test
    void anItemWhoseSlotChangedIsSkippedAndTheJobStillCompletes() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        world.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        world.runner().tick();

        assertEquals("SKIPPED", world.jobs.stateOf(RollbackBed.JOB, identifier));
        assertEquals("ALREADY_INACTIVE", world.jobs.reasonOf(RollbackBed.JOB, identifier));
        assertEquals("COMPLETED", world.jobs.find(RollbackBed.JOB).orElseThrow().state(), "a skipped item does not fail the job");
    }
}
