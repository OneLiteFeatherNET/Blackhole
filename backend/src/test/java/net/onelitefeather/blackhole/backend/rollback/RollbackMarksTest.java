package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.StaffAuditEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kick in a rollback window is marked as rolled back, never revoked: the mark changes only the kick's metadata,
 * writes an audit entry, and produces no revoke, no ELO change and no slot change.
 */
class RollbackMarksTest {

    private static final String REASON = "compromised account";
    private static final String KICK_OWNER = "cd".repeat(64);

    private final RollbackBed world = new RollbackBed();

    private String givenFrozenKick() {
        String kick = world.givenKick(KICK_OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(kick, KICK_OWNER, "chat", "KICK")));
        return kick;
    }

    private Object metaDataOf(String identifier, String key) {
        return world.bed.punishments.repository().findById(identifier).orElseThrow().getMetaData().get(key);
    }

    private List<StaffAuditEntry> markAudits() {
        return world.bed.auditStore.rows().stream()
                .filter(entry -> AuditAction.KICK_ROLLBACK_MARK.wireName().equals(entry.action()))
                .toList();
    }

    @Test
    void aKickInTheWindowIsMarkedWithTheJobAndItsAdmin() {
        String kick = givenFrozenKick();
        world.execution().start(RollbackBed.JOB, REASON, "key-1", false, true);

        world.runner().tick();

        assertEquals("SKIPPED", world.jobs.stateOf(RollbackBed.JOB, kick));
        assertEquals("KICK_MARKED", world.jobs.reasonOf(RollbackBed.JOB, kick));
        assertEquals(RollbackBed.JOB.toString(), metaDataOf(kick, "rollbackJobId"), "the kick records the job");
        assertEquals(STAFF.toString(), metaDataOf(kick, "rolledBackBy"), "the kick records the admin who asked");
    }

    @Test
    void aMarkedKickIsNotRevokedOrLifted() {
        String kick = givenFrozenKick();
        world.execution().start(RollbackBed.JOB, REASON, "key-1", false, true);

        world.runner().tick();

        assertFalse(world.bed.punishments.repository().findById(kick).orElseThrow().isLifted(), "a mark does not lift the kick");
        assertTrue(world.bed.eloBed.events.eventsOfType("punishment.revoked").isEmpty(), "a mark publishes no punishment.revoked");
    }

    @Test
    void aMarkCostsNoEloAndChangesNoSlot() {
        String active = world.givenActiveMute(OWNER);
        givenFrozenKick();
        world.execution().start(RollbackBed.JOB, REASON, "key-1", false, true);

        world.runner().tick();

        assertTrue(world.bed.eloBed.events.events().stream().noneMatch(event -> event.type().startsWith("elo")),
                "a mark publishes no ELO event");
        assertEquals(active, world.bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("chat").getIdentifier(),
                "the owner's active punishment keeps its slot");
    }

    @Test
    void aKickIsNotMarkedWhenTheAdminDidNotAskForMarks() {
        String kick = givenFrozenKick();
        world.execution().start(RollbackBed.JOB, REASON, "key-1", false, false);

        world.runner().tick();

        assertEquals("SKIPPED", world.jobs.stateOf(RollbackBed.JOB, kick));
        assertEquals("MARKS_NOT_REQUESTED", world.jobs.reasonOf(RollbackBed.JOB, kick));
        assertNull(metaDataOf(kick, "rollbackJobId"), "nothing is written to a kick the admin did not mark");
    }

    @Test
    void aMarkIsAuditedOnceEvenWhenTheRunnerRunsTwice() {
        givenFrozenKick();
        world.execution().start(RollbackBed.JOB, REASON, "key-1", false, true);
        var runner = world.runner();

        runner.tick();
        runner.tick();

        assertEquals(1, markAudits().size(), "one kick, one audit entry, however often the runner ticks");
    }

    @Test
    void aMissingKickFailsItsItemAndNotTheWholeRollback() {
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem("kick-gone", KICK_OWNER, "chat", "KICK")));
        world.execution().start(RollbackBed.JOB, REASON, "key-1", false, true);

        world.runner().tick();

        assertEquals("FAILED", world.jobs.stateOf(RollbackBed.JOB, "kick-gone"));
        assertEquals("MARK_FAILED", world.jobs.reasonOf(RollbackBed.JOB, "kick-gone"));
    }
}
