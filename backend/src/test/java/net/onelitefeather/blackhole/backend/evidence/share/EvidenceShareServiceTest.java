package net.onelitefeather.blackhole.backend.evidence.share;

import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakReason;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryShareStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of sharing evidence with the player over in-memory stores: kinds, confirmation, minimisation, repeat, revoke and the audit events. */
class EvidenceShareServiceTest {

    private static final String ID = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final UUID APPEAL = UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a1");

    @TempDir
    Path spool;

    private EvidenceTestBed bed;
    private InMemoryCaseEventRepository log;
    private FakeCaseStore cases;
    private InMemoryShareStore store;
    private RecordingEventPublisher events;
    private EvidenceShareService service;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
        this.log = new InMemoryCaseEventRepository();
        this.cases = new FakeCaseStore(this.log);
        this.store = new InMemoryShareStore(this.log);
        this.events = new RecordingEventPublisher();
        this.bed.punishments.given(new PunishmentEntity(ID, STAFF, OWNER, "network", false, 1L, null, new HashMap<>()), ID);
        this.store.givenAppeal(ID, APPEAL);
        this.service = new EvidenceShareService(this.bed.punishments.repository(), this.bed.items.repository(), this.store, this.cases, this.cases,
                this.events, this.bed.clock, 20);
    }

    private EvidenceItemEntity givenItem(EvidenceKind kind, Map<String, Object> payload) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), ID, OWNER, null, kind, "test", 1L);
        item.setContent(kind == EvidenceKind.LINK ? "https://example.org/x" : null, payload);
        return this.bed.items.repository().save(item);
    }

    private EvidenceItemEntity givenFile(String contentType, EvidenceProvenance provenance) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), ID, OWNER, null, EvidenceKind.FILE, "test", 1L);
        item.setFile("key-" + item.getIdentifier(), "shot.png", contentType, 100, "0".repeat(64));
        item.setProvenance(provenance);
        return this.bed.items.repository().save(item);
    }

    private static Map<String, Object> line(long at, String speaker, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", at);
        line.put("speaker", speaker);
        line.put("text", text);
        return line;
    }

    private EvidenceItemEntity givenCapture() {
        List<Map<String, Object>> lines = new ArrayList<>();
        String[] speakers = {"Alice", "Bob", "Carol"};
        for (int i = 0; i < 25; i++) {
            lines.add(line(1_000_000L + i * 5_000L, speakers[i % 3], "line " + i));
        }
        return givenItem(EvidenceKind.CHAT_LOG, Map.of("lines", lines));
    }

    private EvidenceShareDTO.CreateRequest create(EvidenceItemEntity item, List<Integer> selection, String appellant, boolean confirmed) {
        return new EvidenceShareDTO.CreateRequest(item.getIdentifier(), selection, appellant, confirmed);
    }

    private EvidenceShareDTO.Share shared(EvidenceItemEntity item, List<Integer> selection) {
        EvidenceShareDTO result = this.service.share(ID, create(item, selection, "Alice", true), ACTOR);
        return assertInstanceOf(EvidenceShareDTO.Share.class, result, () -> String.valueOf(result));
    }

    private static void assertError(EvidenceShareDTO result, CaseErrorKind kind, String code) {
        EvidenceShareDTO.Error error = assertInstanceOf(EvidenceShareDTO.Error.class, result, () -> "expected " + code + " but got " + result);
        assertEquals(code, error.code(), error.errorMessage());
        assertEquals(kind, error.kind());
    }

    // ---- kinds ---------------------------------------------------------------------------------------------

    @Test
    void aChatCaptureIsShareableAsAnExcerpt() {
        EvidenceShareDTO.Share share = shared(givenCapture(), List.of(0, 1, 2));

        assertEquals("EXCERPT", share.mode());
        assertEquals(3, share.lineCount());
        assertEquals("ACTIVE", share.state());
        assertFalse(share.repeated());
    }

    @Test
    void aTeamScreenshotIsShareableWhole() {
        EvidenceShareDTO.Share share = shared(givenFile("image/png", EvidenceProvenance.STAFF), List.of());

        assertEquals("FILE", share.mode());
        assertNull(share.lineCount());
    }

    @Test
    void everyOtherKindIsNotShareable() {
        for (EvidenceKind kind : List.of(EvidenceKind.ANTICHEAT, EvidenceKind.REPLAY, EvidenceKind.LINK, EvidenceKind.TRANSCRIPT)) {
            EvidenceItemEntity item = givenItem(kind, Map.of("description", "d"));
            assertError(this.service.share(ID, create(item, List.of(), null, true), ACTOR), CaseErrorKind.UNPROCESSABLE, "NOT_SHAREABLE");
        }
        assertTrue(this.store.rows().isEmpty(), "nothing was shared");
    }

    @Test
    void aVideoAPdfAndAPlayersOwnFileAreNotShareable() {
        for (EvidenceItemEntity item : List.of(givenFile("video/mp4", EvidenceProvenance.STAFF), givenFile("application/pdf", EvidenceProvenance.STAFF),
                givenFile("image/png", EvidenceProvenance.PLAYER))) {
            assertError(this.service.share(ID, create(item, List.of(), null, true), ACTOR), CaseErrorKind.UNPROCESSABLE, "NOT_SHAREABLE");
        }
    }

    @Test
    void liftedEvidenceCannotBeShared() {
        EvidenceItemEntity item = givenFile("image/png", EvidenceProvenance.STAFF);
        item.markStatus(EvidenceStatus.LIFTED, 5L, null);
        this.bed.items.repository().update(item);

        assertError(this.service.share(ID, create(item, List.of(), null, true), ACTOR), CaseErrorKind.CONFLICT, "NOT_VALID");
        assertTrue(this.store.rows().isEmpty());
    }

    @Test
    void evidenceOfAnotherCaseIsNotFound() {
        EvidenceItemEntity foreign = new EvidenceItemEntity(UUID.randomUUID(), "OTHER", OWNER, null, EvidenceKind.CHAT_LOG, "t", 1L);
        this.bed.items.repository().save(foreign);

        assertError(this.service.share(ID, create(foreign, List.of(0), "Alice", true), ACTOR), CaseErrorKind.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void aStagedChatFileIsNotFound() {
        EvidenceItemEntity staged = new EvidenceItemEntity(UUID.randomUUID(), null, OWNER, "chat:x", EvidenceKind.FILE, "chat", 1L);
        this.bed.items.repository().save(staged);

        assertError(this.service.share(ID, create(staged, List.of(), null, true), ACTOR), CaseErrorKind.NOT_FOUND, "NOT_FOUND");
    }

    // ---- preconditions ---------------------------------------------------------------------------------------

    @Test
    void withoutTheConfirmationNothingIsShared() {
        EvidenceItemEntity capture = givenCapture();

        assertError(this.service.share(ID, create(capture, List.of(0), "Alice", false), ACTOR), CaseErrorKind.VALIDATION, "NOT_CONFIRMED");
        assertTrue(this.store.rows().isEmpty());
        assertTrue(this.log.ofType(ID, "evidence.shared").isEmpty());
    }

    @Test
    void aCaseWithoutAnAppealCannotShare() {
        this.store = new InMemoryShareStore(this.log);
        this.service = new EvidenceShareService(this.bed.punishments.repository(), this.bed.items.repository(), this.store, this.cases, this.cases,
                this.events, this.bed.clock, 20);

        assertError(this.service.share(ID, create(givenCapture(), List.of(0), "Alice", true), ACTOR), CaseErrorKind.CONFLICT, "NO_APPEAL");
    }

    @Test
    void aLeakedBanIdFreezesSharing() {
        this.cases.ensureCase(ID, 1L);
        this.cases.markBanIdLeaked(ID, BanIdLeakReason.OTHER, BanIdLeakSource.STAFF, 2L, CaseActor.system());

        assertError(this.service.share(ID, create(givenCapture(), List.of(0), "Alice", true), ACTOR), CaseErrorKind.CONFLICT, "FROZEN");
        assertTrue(this.store.rows().isEmpty());
    }

    // ---- minimisation ------------------------------------------------------------------------------------------

    @Test
    void twentyLinesAreAllowedAndTwentyOneAreNot() {
        EvidenceItemEntity capture = givenCapture();
        List<Integer> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(i);
        }
        List<Integer> twentyOne = new ArrayList<>(twenty);
        twentyOne.add(20);

        assertError(this.service.share(ID, create(capture, twentyOne, "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
        assertEquals(20, shared(capture, twenty).lineCount());
    }

    @Test
    void anEmptyOrOutOfRangeOrRepeatedSelectionIsRefused() {
        EvidenceItemEntity capture = givenCapture();

        assertError(this.service.share(ID, create(capture, List.of(), "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
        assertError(this.service.share(ID, create(capture, null, "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
        assertError(this.service.share(ID, create(capture, List.of(25), "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
        assertError(this.service.share(ID, create(capture, List.of(-1), "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
        assertError(this.service.share(ID, create(capture, List.of(1, 1), "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
    }

    @Test
    void theAppellantMustBeASpeakerOfTheSelectedLines() {
        EvidenceItemEntity capture = givenCapture();

        assertError(this.service.share(ID, create(capture, List.of(1, 2), "Alice", true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
        assertError(this.service.share(ID, create(capture, List.of(0), null, true), ACTOR), CaseErrorKind.VALIDATION, "BAD_SELECTION");
    }

    @Test
    void aLineWithAUuidOrAnIpIsRejectedAndOnlyItsIndexIsNamed() {
        List<Map<String, Object>> lines = List.of(line(1L, "Alice", "fine"),
                line(2L, "Bob", "his id is 123e4567-e89b-12d3-a456-426614174000"), line(3L, "Alice", "and 10.0.0.1"));
        EvidenceItemEntity capture = givenItem(EvidenceKind.CHAT_LOG, Map.of("lines", lines));

        EvidenceShareDTO uuid = this.service.share(ID, create(capture, List.of(0, 1), "Alice", true), ACTOR);
        EvidenceShareDTO ip = this.service.share(ID, create(capture, List.of(2), "Alice", true), ACTOR);

        assertError(uuid, CaseErrorKind.UNPROCESSABLE, "PII_REJECTED");
        assertError(ip, CaseErrorKind.UNPROCESSABLE, "PII_REJECTED");
        String message = ((EvidenceShareDTO.Error) uuid).errorMessage();
        assertTrue(message.contains("line 1"), message);
        assertFalse(message.contains("123e4567") || message.contains("Bob"), "the message names the index only: " + message);
    }

    @Test
    void thePreviewShowsExactlyWhatThePlayerWouldSeeAndChangesNothing() {
        EvidenceItemEntity capture = givenCapture();

        EvidenceShareDTO.Preview preview = assertInstanceOf(EvidenceShareDTO.Preview.class,
                this.service.preview(ID, new EvidenceShareDTO.PreviewRequest(capture.getIdentifier(), List.of(0, 1, 2, 3), "Alice")));

        assertEquals(List.of("You", "Player 2", "Player 3", "You"), preview.lines().stream().map(EvidenceShareDTO.Line::speaker).toList());
        assertEquals(List.of(0L, 5L, 10L, 15L), preview.lines().stream().map(EvidenceShareDTO.Line::offsetSeconds).toList());
        assertEquals("line 2", preview.lines().get(2).text());
        assertTrue(this.store.rows().isEmpty(), "no share");
        assertTrue(this.log.all().isEmpty(), "no event");
    }

    @Test
    void thePreviewAppliesTheSameRulesAsTheShare() {
        EvidenceItemEntity anticheat = givenItem(EvidenceKind.ANTICHEAT, Map.of("check", "x"));

        assertError(this.service.preview(ID, new EvidenceShareDTO.PreviewRequest(anticheat.getIdentifier(), List.of(), null)),
                CaseErrorKind.UNPROCESSABLE, "NOT_SHAREABLE");
    }

    // ---- repeat, revoke, audit --------------------------------------------------------------------------------

    @Test
    void sharingTheSameLinesTwiceGivesOneShare() {
        EvidenceItemEntity capture = givenCapture();
        EvidenceShareDTO.Share first = shared(capture, List.of(0, 3));

        EvidenceShareDTO.Share second = shared(capture, List.of(3, 0));

        assertTrue(second.repeated(), "the second answer says so");
        assertEquals(first.shareId(), second.shareId());
        assertEquals(1, this.store.rows().size());
        assertEquals(1, this.log.ofType(ID, "evidence.shared").size(), "one event");
        assertEquals(1, this.events.eventsOfType("case.evidence_shared").size(), "one domain event");
    }

    @Test
    void aDifferentSelectionIsAnotherShare() {
        EvidenceItemEntity capture = givenCapture();
        shared(capture, List.of(0));
        shared(capture, List.of(0, 3));

        assertEquals(2, this.store.rows().size());
    }

    @Test
    void sharingAndRevokingAreAuditedWithTheVerifiedActorAndNoContent() {
        EvidenceItemEntity capture = givenCapture();
        EvidenceShareDTO.Share share = shared(capture, List.of(0, 1, 2));

        this.service.revoke(ID, share.shareId(), new EvidenceShareDTO.RevokeRequest(ShareRevokeReason.PRIVACY), ACTOR);

        CaseEventRecord shareEvent = this.log.ofType(ID, "evidence.shared").getFirst();
        CaseEventRecord revokeEvent = this.log.ofType(ID, "evidence.share_revoked").getFirst();
        assertEquals("PLAYER", shareEvent.visibility(), "the player's reader may list it");
        assertEquals("PLAYER", revokeEvent.visibility());
        assertEquals(ActorTrust.VERIFIED, shareEvent.actorTrust());
        assertEquals(ACTOR.oid(), shareEvent.actorOid());
        assertEquals(ACTOR.oid(), revokeEvent.actorOid());
        assertEquals("evidence.shared:" + share.shareId(), shareEvent.dedupeKey());
        assertEquals("evidence.share_revoked:" + share.shareId(), revokeEvent.dedupeKey());
        assertEquals(Map.of("shareId", share.shareId().toString(), "evidenceKind", "CHAT_LOG", "mode", "EXCERPT", "lineCount", 3), shareEvent.body());
        assertEquals(Map.of("shareId", share.shareId().toString(), "reason", "PRIVACY"), revokeEvent.body());
        String everything = shareEvent.body() + " " + revokeEvent.body() + " " + this.events.events();
        assertFalse(everything.contains("line 0") || everything.contains("Alice") || everything.contains("Bob"), "no content in any event: " + everything);
        assertEquals(1, this.events.eventsOfType("case.evidence_share_revoked").size());
    }

    @Test
    void revokingTwiceChangesNothingAndAnswersRevoked() {
        EvidenceShareDTO.Share share = shared(givenCapture(), List.of(0));
        this.service.revoke(ID, share.shareId(), new EvidenceShareDTO.RevokeRequest(ShareRevokeReason.MISTAKE), ACTOR);

        EvidenceShareDTO.Share again = assertInstanceOf(EvidenceShareDTO.Share.class,
                this.service.revoke(ID, share.shareId(), new EvidenceShareDTO.RevokeRequest(ShareRevokeReason.OTHER), ACTOR));

        assertEquals("REVOKED", again.state());
        assertEquals(ShareRevokeReason.MISTAKE, again.revokeReason(), "the first reason stands");
        assertEquals(1, this.log.ofType(ID, "evidence.share_revoked").size());
        assertEquals(1, this.events.eventsOfType("case.evidence_share_revoked").size());
    }

    @Test
    void anUnknownShareIsNotFoundAndARevokeNeedsAReason() {
        EvidenceShareDTO.Share share = shared(givenCapture(), List.of(0));

        assertError(this.service.revoke(ID, UUID.randomUUID(), new EvidenceShareDTO.RevokeRequest(ShareRevokeReason.OTHER), ACTOR),
                CaseErrorKind.NOT_FOUND, "NOT_FOUND");
        assertError(this.service.revoke(ID, share.shareId(), new EvidenceShareDTO.RevokeRequest(null), ACTOR), CaseErrorKind.VALIDATION, "NO_REASON");
    }

    @Test
    void aRevokedLineSelectionCanBeSharedAgain() {
        EvidenceItemEntity capture = givenCapture();
        EvidenceShareDTO.Share first = shared(capture, List.of(0));
        this.service.revoke(ID, first.shareId(), new EvidenceShareDTO.RevokeRequest(ShareRevokeReason.MISTAKE), ACTOR);

        EvidenceShareDTO.Share again = shared(capture, List.of(0));

        assertFalse(again.repeated());
        assertFalse(first.shareId().equals(again.shareId()));
    }

    @Test
    void deletedEvidenceReadsAsUnavailable() {
        EvidenceItemEntity capture = givenCapture();
        EvidenceShareDTO.Share share = shared(capture, List.of(0));
        this.bed.items.repository().delete(capture);

        EvidenceShareDTO.ShareList list = assertInstanceOf(EvidenceShareDTO.ShareList.class, this.service.list(ID));

        assertEquals(share.shareId(), list.items().getFirst().shareId());
        assertEquals("UNAVAILABLE", list.items().getFirst().state());
    }

    @Test
    void theListShowsStatesOldestFirst() {
        EvidenceItemEntity capture = givenCapture();
        EvidenceShareDTO.Share first = shared(capture, List.of(0));
        shared(capture, List.of(0, 3));
        this.service.revoke(ID, first.shareId(), new EvidenceShareDTO.RevokeRequest(ShareRevokeReason.MISTAKE), ACTOR);

        EvidenceShareDTO.ShareList list = assertInstanceOf(EvidenceShareDTO.ShareList.class, this.service.list(ID));

        assertEquals(List.of("REVOKED", "ACTIVE"), list.items().stream().map(EvidenceShareDTO.Share::state).toList());
    }

    @Test
    void theConfirmingOidIsStoredWithTheShare() {
        shared(givenCapture(), List.of(0));

        assertEquals(ACTOR.oid(), this.store.rows().getFirst().sharedByOid());
        assertTrue(this.store.rows().getFirst().confirmedMinimised());
        assertEquals(List.of(0), this.store.rows().getFirst().selection(), "line indexes only, no copy of the lines");
    }

    @Test
    void anUnknownPunishmentIsNotFound() {
        assertError(this.service.list("missing"), CaseErrorKind.NOT_FOUND, "NOT_FOUND");
    }
}
