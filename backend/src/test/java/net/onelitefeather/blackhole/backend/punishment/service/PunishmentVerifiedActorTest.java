package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.PROXY;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who a punishment is attributed to, and which verified principal recorded it. */
class PunishmentVerifiedActorTest {

    private static final UUID INGAME_STAFF = UUID.nameUUIDFromBytes("in-game-staff".getBytes());

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentDTO apply(PunishmentDTO.ApplyRequest request, net.onelitefeather.blackhole.backend.security.Actor actor) {
        return this.bed.punishmentService.apply(request, actor);
    }

    private PunishmentEntity onlyPunishment() {
        assertEquals(1, this.bed.punishments.all().size(), "exactly one punishment is stored");
        return this.bed.punishments.all().getFirst();
    }

    @Test
    void aStaffMemberIsTheSourceAndTheRecordedPrincipalOfTheirPunishment() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        assertEquals(ACTOR.oid(), onlyPunishment().getSource(), "attributed to the token's oid");
        assertEquals(ACTOR.oid(), onlyPunishment().getRecordedByOid(), "recorded by the token's oid");
    }

    @Test
    void theProxyWithoutOnBehalfOfIsAttributedToItself() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), PROXY);

        assertEquals(PROXY.oid(), onlyPunishment().getSource());
        assertEquals(PROXY.oid(), onlyPunishment().getRecordedByOid());
    }

    @Test
    void theProxyCanVouchForAnInGameStaffMember() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, INGAME_STAFF), PROXY);

        assertEquals(INGAME_STAFF, onlyPunishment().getSource(), "attributed to the delegated in-game UUID");
        assertEquals(PROXY.oid(), onlyPunishment().getRecordedByOid(), "still recorded by the proxy's verified oid");
    }

    @Test
    void aStaffMemberSendingOnBehalfOfIsRejectedAndNothingIsWritten() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        PunishmentDTO result = apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, INGAME_STAFF), ACTOR);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
        assertTrue(this.bed.punishments.all().isEmpty(), "no punishment is stored");
    }

    @Test
    void aPreviewWithOnBehalfOfFromAStaffMemberIsRejectedLikeTheApply() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        PunishmentDTO result = this.bed.punishmentService.preview(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, INGAME_STAFF), ACTOR);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
    }

    @Test
    void aRevokeByTheProxyRecordsTheDelegatedStaffMemberAndTheProxyOid() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", INGAME_STAFF), PROXY);

        var revoked = this.bed.profiles.repository().findById(OWNER).orElseThrow().getHistory().getFirst();
        assertEquals(INGAME_STAFF.toString(), revoked.getMetaData().get("revokedBy"));
        assertEquals(PROXY.oid().toString(), revoked.getMetaData().get("recordedByOid"));
    }

    @Test
    void theRevokedEventNamesTheVerifiedPrincipalAndMarksItVerified() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        var event = this.bed.eloBed.events.eventsOfType("punishment.revoked").getFirst();
        assertEquals(ACTOR.oid().toString(), event.payload().get("recordedByOid"));
        assertEquals(true, event.payload().get("verified"), "a revoke with a token is verified");
    }

    @Test
    void aRevokeWithOnBehalfOfFromAStaffMemberIsRejectedAndThePunishmentStaysActive() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        PunishmentDTO result = this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", INGAME_STAFF), ACTOR);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
        assertTrue(this.bed.profiles.repository().findById(OWNER).orElseThrow().getActive().containsKey("chat"), "still active");
    }

    @Test
    void aPunishmentWrittenBeforeSsoIsReportedAsUnverified() {
        PunishmentEntity legacy = new PunishmentEntity("legacy", INGAME_STAFF, OWNER, "chat", false, 1L, null, new HashMap<>());

        var dto = legacy.toDTO();

        assertEquals(INGAME_STAFF, dto.source(), "the legacy attribution is unchanged");
        assertNull(dto.recordedByOid(), "no verified principal");
        assertFalse(dto.verified(), "reported as unverified");
    }

    @Test
    void aPunishmentWrittenWithATokenIsReportedAsVerified() {
        PunishmentEntity fresh = new PunishmentEntity("fresh", ACTOR.oid(), OWNER, "chat", false, 1L, null, new HashMap<>());
        fresh.setRecordedByOid(ACTOR.oid());

        assertTrue(fresh.toDTO().verified());
        assertEquals(ACTOR.oid(), fresh.toDTO().recordedByOid());
    }
}
