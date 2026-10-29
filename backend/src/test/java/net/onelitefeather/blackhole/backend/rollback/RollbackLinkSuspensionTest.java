package net.onelitefeather.blackhole.backend.rollback;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackLinkSuspensionTest {

    private static final UUID MINECRAFT = UUID.nameUUIDFromBytes("minecraft-of-staff".getBytes());

    private final RollbackBed world = new RollbackBed();

    private void givenPreviewWithALinkedStaffMember() {
        world.bed.staffLinks.givenLink(STAFF, MINECRAFT);
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
    }

    @Test
    void aSuspendedLinkIsRemovedWithTheRollbackReasonBeforeTheItemsRun() {
        givenPreviewWithALinkedStaffMember();

        world.execution().start(RollbackBed.JOB, "compromised account", "key-1", true);

        assertTrue(world.bed.staffLinks.linkOf(STAFF).isEmpty(), "the Minecraft link of the staff member is gone");
        var removed = world.bed.eloBed.events.eventsOfType("staff-link.removed");
        assertEquals(1, removed.size(), "one removal is published");
        assertEquals("ROLLBACK", removed.getFirst().payload().get("reason"));
        assertEquals("RUNNING", world.jobs.find(RollbackBed.JOB).orElseThrow().state(), "the job is running after the start");
    }

    @Test
    void aRollbackWithoutSuspensionKeepsTheLink() {
        givenPreviewWithALinkedStaffMember();

        world.execution().start(RollbackBed.JOB, "compromised account", "key-1", false);

        assertFalse(world.bed.staffLinks.linkOf(STAFF).isEmpty(), "the link stays unless the admin asked for suspension");
        assertTrue(world.bed.eloBed.events.eventsOfType("staff-link.removed").isEmpty());
    }
}
