package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;

/**
 * One rollback test's world: a fresh punishment test bed, an in-memory job store and helpers that create a
 * frozen preview and drive the runner. Built per test, so nothing is shared between tests.
 */
final class RollbackBed {

    static final UUID JOB = UUID.nameUUIDFromBytes("rollback-job".getBytes());
    static final String SECOND_OWNER = "cd".repeat(64);
    static final String THIRD_OWNER = "ef".repeat(64);
    static final Duration HOUR = Duration.ofHours(1);

    final PunishmentTestBed bed = new PunishmentTestBed();
    final InMemoryRollbackJobStore jobs = new InMemoryRollbackJobStore();
    private boolean templateGiven;

    /** Applies a temporary chat mute to {@code owner} as the staff member and returns its identifier. */
    String givenActiveMute(String owner) {
        if (!this.templateGiven) {
            this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
            this.templateGiven = true;
        }
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(owner, "spam", null, null), ACTOR);
        return bed.profiles.repository().findById(owner).orElseThrow().getActive().get("chat").getIdentifier();
    }

    /** Stores a network kick of {@code owner} by the staff member and returns its identifier. A kick is never active. */
    String givenKick(String owner) {
        return this.bed.kickService.kickForTemplate(owner, KickScope.NETWORK, null, "rule break", new Attribution(STAFF, STAFF),
                null, "kick-group", 0, 1, 0, Map.of()).getIdentifier();
    }

    /** Freezes the given identifiers of their owners into a preview job made by the staff member. */
    void givenPreview(List<RollbackJobStore.FrozenItem> items) {
        long now = bed.eloBed.clock.millis();
        this.jobs.insertPreview(new RollbackJobStore.PreviewJob(JOB, STAFF, RollbackSelector.staffActor(STAFF), 0L, now + 1L,
                now, now + HOUR.toMillis()), items);
    }

    RollbackExecutionService execution() {
        return new RollbackExecutionService(this.jobs, this.bed.eloBed.events, this.bed.eloBed.clock, this.bed.staffLinks.service);
    }

    RollbackRunner runner(String holder, Duration lease, int batchSize) {
        return runner(this.bed.punishmentService::revokeHeld, holder, lease, batchSize);
    }

    RollbackRunner runner(RollbackRunner.ItemRevoke revoke, String holder, Duration lease, int batchSize) {
        return new RollbackRunner(this.jobs, revoke, this.bed.punishmentService::markRolledBack, this.bed.eloBed.events,
                this.bed.eloBed.clock, holder, lease, batchSize);
    }

    RollbackRunner runner() {
        return runner("runner-a", Duration.ofMinutes(2), 50);
    }
}
