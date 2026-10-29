package net.onelitefeather.blackhole.backend.rollback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackRevokeReuseTest {

    private final RollbackBed world = new RollbackBed();

    @Test
    void aRolledBackItemIsRevokedByTheAdminLikeAManualRevoke() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        world.runner().tick();

        var profile = world.bed.profiles.repository().findById(OWNER).orElseThrow();
        assertFalse(profile.getActive().containsKey("chat"), "the slot is cleared as by a manual revoke");
        var punishment = profile.getHistory().getFirst();
        assertTrue(punishment.isLifted(), "the punishment is lifted");
        assertEquals(STAFF.toString(), punishment.getMetaData().get("revokedBy"), "the admin is the revoker");
        assertEquals(RollbackBed.JOB.toString(), punishment.getMetaData().get("rollbackJobId"), "the job is recorded on the punishment");
        assertEquals("compromised account", punishment.getMetaData().get("rollbackReason"), "the reason is recorded on the punishment");
    }

    @Test
    void aRolledBackItemPublishesTheSameRevocationAsAManualRevoke() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        world.runner().tick();

        var event = world.bed.eloBed.events.eventsOfType("punishment.revoked").getFirst();
        assertEquals("chat", event.payload().get("section"));
        assertEquals("MUTE", event.payload().get("enforcement"));
        assertEquals(STAFF.toString(), event.payload().get("revokedBy"));
        assertEquals(OWNER, event.payload().get("owner"));
    }

    @Test
    void aRolledBackItemMarksItsEvidenceRevoked() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        world.execution().start(RollbackBed.JOB, "compromised account", "key-1");

        world.runner().tick();

        assertEquals(List.of(identifier), world.bed.evidence.revoked(), "the evidence of the rolled-back punishment is marked");
    }
}
