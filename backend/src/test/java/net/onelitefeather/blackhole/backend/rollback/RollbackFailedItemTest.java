package net.onelitefeather.blackhole.backend.rollback;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RollbackFailedItemTest {

    private final RollbackBed world = new RollbackBed();

    @Test
    void aFailingItemIsMarkedFailedAndTheOthersStillComplete() {
        String first = world.givenActiveMute(OWNER);
        String failing = world.givenActiveMute(RollbackBed.SECOND_OWNER);
        String third = world.givenActiveMute(RollbackBed.THIRD_OWNER);
        world.givenPreview(List.of(
                new RollbackJobStore.FrozenItem(first, OWNER, "chat", "PUNISHMENT"),
                new RollbackJobStore.FrozenItem(failing, RollbackBed.SECOND_OWNER, "chat", "PUNISHMENT"),
                new RollbackJobStore.FrozenItem(third, RollbackBed.THIRD_OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");
        RollbackRunner.ItemRevoke real = world.bed.punishmentService::revokeHeld;
        RollbackRunner.ItemRevoke failingForOne = (owner, section, identifier, attribution, actor, jobId, reason) -> {
            if (identifier.equals(failing)) {
                throw new IllegalStateException("database down");
            }
            return real.revoke(owner, section, identifier, attribution, actor, jobId, reason);
        };

        world.runner(failingForOne, "runner-a", Duration.ofMinutes(2), 50).tick();

        assertEquals("REVOKED", world.jobs.stateOf(RollbackBed.JOB, first), "the item before the failure is revoked");
        assertEquals("FAILED", world.jobs.stateOf(RollbackBed.JOB, failing), "the failing item is marked FAILED");
        assertEquals("REVOKE_FAILED", world.jobs.reasonOf(RollbackBed.JOB, failing), "a code, never a message");
        assertEquals("REVOKED", world.jobs.stateOf(RollbackBed.JOB, third), "the item after the failure still proceeds");
        assertEquals("COMPLETED", world.jobs.find(RollbackBed.JOB).orElseThrow().state(),
                "a failed item does not keep the job from completing");
    }
}
