package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which staff member an in-game punishment is attributed to, through the live link of the delegated UUID. */
class StaffLinkAttributionTest {

    private static final UUID STAFF_OID = UUID.nameUUIDFromBytes("linked-staff".getBytes());
    private static final UUID INGAME = UUID.nameUUIDFromBytes("ingame-staff".getBytes());
    private static final Actor PROXY = new Actor(UUID.nameUUIDFromBytes("proxy".getBytes()), null, ActorKind.SERVICE, true);

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentDTO.ApplyResponse apply(UUID onBehalfOf, Actor actor) {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class,
                this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, onBehalfOf), actor));
    }

    private PunishmentEntity stored(PunishmentDTO.ApplyResponse applied) {
        return this.bed.punishments.all().stream()
                .filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst().orElseThrow();
    }

    @Test
    void anInGamePunishmentByALinkedUuidKeepsTheUuidAsSourceTheProxyAsRecorderAndTheStaffMemberAsActor() {
        this.bed.staffLinks.givenActor(STAFF_OID, "Linda Linked");
        this.bed.staffLinks.givenLink(STAFF_OID, INGAME);

        PunishmentDTO.ApplyResponse applied = apply(INGAME, PROXY);

        PunishmentEntity row = stored(applied);
        assertEquals(INGAME, row.getSource(), "source is the unverified uuid, never rewritten");
        assertEquals(PROXY.oid(), row.getRecordedByOid(), "recorded by the proxy");
        assertEquals(STAFF_OID, row.getActorOid(), "attributed to the linked staff member");
        assertEquals(STAFF_OID, applied.punishment().actorOid(), "the response exposes actorOid");
        assertEquals("Linda Linked", applied.punishment().actorName(), "and the staff member's name");
    }

    @Test
    void anInGamePunishmentByAnUnlinkedUuidHasNoActorOid() {
        PunishmentDTO.ApplyResponse applied = apply(INGAME, PROXY);

        assertNull(stored(applied).getActorOid(), "stored NULL");
        assertNull(applied.punishment().actorOid(), "exposed as null");
        assertNull(applied.punishment().actorName(), "so the timeline shows the uuid");
    }

    @Test
    void aWebPunishmentHasNoActorOidBecauseRecordedByOidAlreadyIsThePerson() {
        this.bed.staffLinks.givenLink(ACTOR.oid(), INGAME);

        PunishmentDTO.ApplyResponse applied = apply(null, ACTOR);

        assertNull(stored(applied).getActorOid(), "no duplicate of recorded_by_oid");
        assertEquals(ACTOR.oid(), stored(applied).getRecordedByOid(), "the person is the recorder");
    }

    @Test
    void creatingALinkLaterDoesNotRewriteEarlierPunishments() {
        PunishmentDTO.ApplyResponse before = apply(INGAME, PROXY);

        this.bed.staffLinks.givenLink(STAFF_OID, INGAME);

        assertNull(stored(before).getActorOid(), "history is append-only");
    }

    @Test
    void anAnonymizedStaffMemberKeepsTheActorOidButShowsNoName() {
        this.bed.staffLinks.givenActor(STAFF_OID, null);
        this.bed.staffLinks.givenLink(STAFF_OID, INGAME);

        PunishmentDTO.ApplyResponse applied = apply(INGAME, PROXY);

        assertEquals(STAFF_OID, applied.punishment().actorOid(), "the oid stays");
        assertNull(applied.punishment().actorName(), "the name is gone");
    }
}
