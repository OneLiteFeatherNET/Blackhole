package net.onelitefeather.blackhole.backend.rollback;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackResumeTest {

    private final RollbackBed world = new RollbackBed();

    @Test
    void aLeaseOfADeadRunnerBlocksUntilItExpiresOnTheClock() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");
        long now = world.bed.eloBed.clock.millis();
        world.jobs.claimRunningJob("dead-runner", now, now + Duration.ofMinutes(2).toMillis());
        RollbackRunner survivor = world.runner("survivor", Duration.ofMinutes(2), 50);

        assertFalse(survivor.tick(), "the dead runner still holds the lease");
        world.bed.eloBed.clock.advance(Duration.ofMinutes(3));

        assertTrue(survivor.tick(), "after the lease expired the survivor takes the job over");
        assertEquals("REVOKED", world.jobs.stateOf(RollbackBed.JOB, identifier));
    }

    @Test
    void aResumedRunNeverRevokesAnItemAgain() {
        String first = world.givenActiveMute(OWNER);
        String second = world.givenActiveMute(RollbackBed.SECOND_OWNER);
        world.givenPreview(List.of(
                new RollbackJobStore.FrozenItem(first, OWNER, "chat", "PUNISHMENT"),
                new RollbackJobStore.FrozenItem(second, RollbackBed.SECOND_OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        // One item per tick: the first runner stops after one item, as if it had died.
        world.runner("first", Duration.ofMinutes(2), 1).tick();
        assertEquals(1, world.bed.eloBed.events.eventsOfType("punishment.revoked").size(), "precondition: one item done");
        world.bed.eloBed.clock.advance(Duration.ofMinutes(3));
        world.runner("second", Duration.ofMinutes(2), 1).tick();
        world.runner("second", Duration.ofMinutes(2), 1).tick();

        assertEquals(2, world.bed.eloBed.events.eventsOfType("punishment.revoked").size(), "each item is revoked exactly once");
        assertEquals("REVOKED", world.jobs.stateOf(RollbackBed.JOB, first));
        assertEquals("REVOKED", world.jobs.stateOf(RollbackBed.JOB, second));
    }
}
