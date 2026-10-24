package net.onelitefeather.blackhole.backend.evidence.service;

import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner;
import net.onelitefeather.blackhole.backend.evidence.upload.ChatFixtures;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OTHER_OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Staging, binding, counting and erasing chat files over in-memory stores, a fake scanner and a clock a test moves. */
class ChatFileServiceTest {

    private static final String PUNISHMENT = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final UUID APPEAL = UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a1");
    private static final String SCOPE = ChatFiles.appealScope(APPEAL);
    private static final Attribution STAFF_ATTRIBUTION = ACTOR.attribution();

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
        this.bed.punishments.given(new PunishmentEntity(PUNISHMENT, STAFF, OWNER, "network", false, 1L, null, new HashMap<>()), PUNISHMENT);
    }

    private static byte[] png(int width, int height) {
        try {
            return EvidenceFixtures.png(width, height);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private EvidenceDTO stage(byte[] content, String name) {
        return this.bed.chatFiles.stage(new ChatFileService.Stage(PUNISHMENT, SCOPE, EvidenceProvenance.PLAYER, null, name),
                new ByteArrayInputStream(content));
    }

    private EvidenceDTO.Staged staged(byte[] content, String name) {
        EvidenceDTO result = stage(content, name);
        return assertInstanceOf(EvidenceDTO.Staged.class, result, () -> String.valueOf(result));
    }

    private static void assertError(EvidenceDTO result, EvidenceErrorKind kind) {
        EvidenceDTO.Error error = assertInstanceOf(EvidenceDTO.Error.class, result, () -> "expected " + kind + " but got " + result);
        assertEquals(kind, error.kind(), error.errorMessage());
    }

    /** What a message does to a staged file, in the way the SQL claim does: the punishment is set, the chat scope stays. */
    private void bind(UUID attachmentId) {
        EvidenceItemEntity staged = this.bed.items.all().stream().filter(item -> item.getIdentifier().equals(attachmentId)).findFirst().orElseThrow();
        EvidenceItemEntity bound = new EvidenceItemEntity(staged.getIdentifier(), PUNISHMENT, staged.getOwner(), staged.getCorrelationId(),
                staged.getKind(), staged.getSourceSystem(), staged.getCreatedAt());
        bound.setProvenance(staged.getProvenance());
        bound.setFile(staged.getFileKey(), staged.getFileName(), staged.getFileContentType(), staged.getFileSize(), staged.getFileSha256());
        this.bed.items.repository().update(bound);
    }

    private EvidenceItemEntity givenStaffEvidence(String punishment) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishment, OWNER, null, EvidenceKind.LINK, "test", this.bed.clock.millis());
        this.bed.items.repository().save(item);
        return item;
    }

    // ---- staging -------------------------------------------------------------------------------------------

    @Test
    void aFileIsStagedAsAPendingItemOfTheChatScope() {
        EvidenceDTO.Staged result = staged(png(32, 20), "shot.png");

        assertEquals("shot.png", result.name());
        assertEquals("image/png", result.contentType());
        assertEquals(32, result.width());
        assertEquals(20, result.height());
        EvidenceItemEntity item = this.bed.items.all().getFirst();
        assertEquals(result.attachmentId(), item.getIdentifier());
        assertNull(item.getPunishmentIdentifier(), "a staged file belongs to no punishment yet");
        assertEquals(SCOPE, item.getCorrelationId());
        assertEquals(OWNER, item.getOwner(), "the owner is the punishment's hashed owner");
        assertEquals(EvidenceProvenance.PLAYER, item.getProvenance());
        assertEquals(EvidenceKind.FILE, item.getKind());
        assertTrue(item.isStagedChatFile());
        assertEquals(1, this.bed.blobs.keys().size(), "the bytes are stored once, after the scan");
    }

    @Test
    void aStagedFileIsInNoListingCountOwnerListingOrContentRoute() {
        EvidenceDTO.Staged result = staged(png(8, 8), "a.png");

        EvidenceDTO.PageResponse byPunishment = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.list(PUNISHMENT, true, Pageable.from(0, 20), ACTOR));
        EvidenceDTO.PageResponse byOwner = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.listByOwner(OWNER, true, Pageable.from(0, 20), ACTOR));
        assertEquals(0, byPunishment.items().size(), "no punishment listing");
        assertEquals(0, byOwner.items().size(), "no owner listing");
        assertEquals(0, this.bed.service.countValid(PUNISHMENT), "not counted");
        assertEquals(0, this.bed.service.countPlayerSupplied(PUNISHMENT), "not counted as player-supplied either");
        assertError(this.bed.service.get(result.attachmentId(), ACTOR), EvidenceErrorKind.NOT_FOUND);
        assertError(this.bed.service.content(result.attachmentId(), null, ACTOR), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void aFlaggedFileLeavesNothingBehind() {
        this.bed.scanner.verdict = new EvidenceScanner.Infected("Eicar-Signature");

        assertError(stage(png(8, 8), "a.png"), EvidenceErrorKind.FILE_FLAGGED);

        assertTrue(this.bed.blobs.keys().isEmpty(), "no blob exists");
        assertTrue(this.bed.items.all().isEmpty(), "no row exists");
    }

    @Test
    void anUnavailableScannerLeavesNothingBehind() {
        this.bed.scanner.verdict = new EvidenceScanner.Unavailable("down");

        assertError(stage(png(8, 8), "a.png"), EvidenceErrorKind.UNAVAILABLE);

        assertTrue(this.bed.blobs.keys().isEmpty());
        assertTrue(this.bed.items.all().isEmpty());
    }

    @Test
    void aFileOfTheWrongTypeIsNotStored() {
        assertError(stage(EvidenceFixtures.mp4(4096), "clip.mp4"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);

        assertTrue(this.bed.blobs.keys().isEmpty());
        assertTrue(this.bed.items.all().isEmpty());
    }

    @Test
    void anUploadOverTheLimitIsTooLargeAndStoresNothing() {
        this.bed.chatConfig.setMaxBytes(500);

        assertError(stage(ChatFixtures.utf8("x".repeat(501)), "big.txt"), EvidenceErrorKind.PAYLOAD_TOO_LARGE);

        assertTrue(this.bed.blobs.keys().isEmpty());
    }

    @Test
    void anUnknownPunishmentIsNotFound() {
        EvidenceDTO result = this.bed.chatFiles.stage(new ChatFileService.Stage("missing", SCOPE, EvidenceProvenance.PLAYER, null, "a.png"),
                new ByteArrayInputStream(png(8, 8)));

        assertError(result, EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void aStaffUploadCarriesTheVerifiedActorAndStaffProvenance() {
        EvidenceDTO result = this.bed.chatFiles.stage(
                new ChatFileService.Stage(PUNISHMENT, ChatFiles.caseScope(PUNISHMENT), EvidenceProvenance.STAFF, STAFF_ATTRIBUTION, "a.png"),
                new ByteArrayInputStream(png(8, 8)));

        assertInstanceOf(EvidenceDTO.Staged.class, result);
        EvidenceItemEntity item = this.bed.items.all().getFirst();
        assertEquals(EvidenceProvenance.STAFF, item.getProvenance());
        assertEquals(STAFF, item.getRecordedByOid(), "the verified principal is recorded");
        assertEquals("chat:" + PUNISHMENT, item.getCorrelationId());
    }

    @Test
    void stagingIsAudited() {
        staged(png(8, 8), "a.png");

        assertEquals(EvidenceAuditAction.ATTACHED, this.bed.audit.all().getFirst().getAction());
    }

    @Test
    void aStagedFileThatNoMessageBoundIsSweptAfterThePendingTtl() {
        EvidenceDTO.Staged result = staged(png(8, 8), "a.png");
        this.bed.clock.advance(this.bed.config.getPendingTtl().plus(Duration.ofMinutes(1)));

        this.bed.service.deleteUnclaimedCreatedBefore(this.bed.clock.millis() - this.bed.config.getPendingTtl().toMillis());

        assertTrue(this.bed.items.all().isEmpty(), "the row of " + result.attachmentId() + " is gone");
        assertTrue(this.bed.blobs.keys().isEmpty(), "and so is the blob");
    }

    // ---- quota ---------------------------------------------------------------------------------------------

    @Test
    void theStagedLimitIsAQuotaNotARate() {
        this.bed.chatConfig.setMaxStaged(2);
        staged(png(8, 8), "a.png");
        staged(png(9, 9), "b.png");

        assertError(stage(png(10, 10), "c.png"), EvidenceErrorKind.QUOTA);
        assertEquals(2, this.bed.items.all().size(), "the third file is not stored");
    }

    @Test
    void theLimitPerAppealCountsSentFilesToo() {
        this.bed.chatConfig.setMaxFilesPerAppeal(2);
        bind(staged(png(8, 8), "a.png").attachmentId());
        staged(png(9, 9), "b.png");

        assertError(stage(png(10, 10), "c.png"), EvidenceErrorKind.QUOTA);
    }

    @Test
    void theQuotaOfOneAppealDoesNotLimitAnother() {
        this.bed.chatConfig.setMaxStaged(1);
        staged(png(8, 8), "a.png");

        EvidenceDTO other = this.bed.chatFiles.stage(
                new ChatFileService.Stage(PUNISHMENT, ChatFiles.appealScope(UUID.randomUUID()), EvidenceProvenance.PLAYER, null, "b.png"),
                new ByteArrayInputStream(png(9, 9)));

        assertInstanceOf(EvidenceDTO.Staged.class, other);
    }

    @Test
    void removingAStagedFileFreesItsSlotAndItsBlob() {
        EvidenceDTO.Staged result = staged(png(8, 8), "a.png");

        assertTrue(this.bed.chatFiles.unstage(SCOPE, result.attachmentId()));

        assertTrue(this.bed.items.all().isEmpty());
        assertTrue(this.bed.blobs.keys().isEmpty());
    }

    @Test
    void aFileOfAnotherScopeCannotBeUnstaged() {
        EvidenceDTO.Staged result = staged(png(8, 8), "a.png");

        assertFalse(this.bed.chatFiles.unstage(ChatFiles.appealScope(UUID.randomUUID()), result.attachmentId()));
        assertEquals(1, this.bed.items.all().size());
    }

    @Test
    void aFileThatAMessageBoundCannotBeUnstaged() {
        EvidenceDTO.Staged result = staged(png(8, 8), "a.png");
        bind(result.attachmentId());

        assertFalse(this.bed.chatFiles.unstage(SCOPE, result.attachmentId()));
        assertEquals(1, this.bed.items.all().size());
    }

    // ---- resolving what a message names --------------------------------------------------------------------

    @Test
    void aMessageMayNameItsOwnStagedFiles() {
        EvidenceDTO.Staged first = staged(png(8, 6), "a.png");
        EvidenceDTO.Staged second = staged(ChatFixtures.utf8("log"), "log.txt");

        ChatFiles.Resolved resolved = this.bed.chatFiles.resolve(PUNISHMENT, SCOPE, List.of(second.attachmentId(), first.attachmentId()));

        assertTrue(resolved.ok(), resolved.problem());
        assertEquals(List.of("log.txt", "a.png"), resolved.files().stream().map(ChatFiles.File::name).toList(), "in the order named");
        assertEquals(8, resolved.files().get(1).width(), "images carry their size into the payload");
        assertEquals(EvidenceProvenance.PLAYER, resolved.files().getFirst().provenance());
    }

    @Test
    void aFileOfAnotherAppealIsRefused() {
        EvidenceDTO.Staged foreign = staged(png(8, 8), "a.png");

        ChatFiles.Resolved resolved = this.bed.chatFiles.resolve(PUNISHMENT, ChatFiles.appealScope(UUID.randomUUID()), List.of(foreign.attachmentId()));

        assertFalse(resolved.ok(), "another appeal's staged file must not be claimable");
        assertTrue(resolved.files().isEmpty());
    }

    @Test
    void aFileThatWasSentAlreadyIsRefused() {
        EvidenceDTO.Staged used = staged(png(8, 8), "a.png");
        bind(used.attachmentId());

        assertFalse(this.bed.chatFiles.resolve(PUNISHMENT, SCOPE, List.of(used.attachmentId())).ok(), "none is used twice");
    }

    @Test
    void anUnknownIdAndAnEvidenceIdOfTheTeamAreRefusedWithTheSameAnswer() {
        EvidenceItemEntity evidence = givenStaffEvidence(PUNISHMENT);

        ChatFiles.Resolved unknown = this.bed.chatFiles.resolve(PUNISHMENT, SCOPE, List.of(UUID.randomUUID()));
        ChatFiles.Resolved team = this.bed.chatFiles.resolve(PUNISHMENT, SCOPE, List.of(evidence.getIdentifier()));

        assertFalse(unknown.ok());
        assertEquals(unknown.problem(), team.problem(), "nothing to probe: the answer does not tell which case it was");
    }

    @Test
    void aStagedFileOfAnotherOwnerIsRefused() {
        EvidenceDTO.Staged staged = staged(png(8, 8), "a.png");
        this.bed.punishments.given(new PunishmentEntity("CCCCCCCCCCCCCCCCCCCCCC", STAFF, OTHER_OWNER, "network", false, 1L, null, new HashMap<>()),
                "CCCCCCCCCCCCCCCCCCCCCC");

        assertFalse(this.bed.chatFiles.resolve("CCCCCCCCCCCCCCCCCCCCCC", SCOPE, List.of(staged.attachmentId())).ok());
    }

    // ---- becoming evidence ---------------------------------------------------------------------------------

    @Test
    void aBoundPlayerFileIsRecordedAsEvidenceAttachedByThePlayer() {
        EvidenceDTO.Staged file = staged(png(8, 8), "a.png");
        bind(file.attachmentId());

        this.bed.chatFiles.bound(PUNISHMENT, List.of(file.attachmentId()));

        List<CaseEventRecord> recorded = this.bed.cases.log.ofType(PUNISHMENT, "evidence.attached");
        assertEquals(1, recorded.size());
        assertEquals(ActorTrust.PLAYER, recorded.getFirst().actorTrust(), "a player's file is not attributed to a staff member");
        assertEquals("evidence.attached:" + file.attachmentId(), recorded.getFirst().dedupeKey());
    }

    @Test
    void recordingTheSameBindTwiceAppendsOneEvent() {
        EvidenceDTO.Staged file = staged(png(8, 8), "a.png");
        bind(file.attachmentId());

        this.bed.chatFiles.bound(PUNISHMENT, List.of(file.attachmentId()));
        this.bed.chatFiles.bound(PUNISHMENT, List.of(file.attachmentId()));

        assertEquals(1, this.bed.cases.log.ofType(PUNISHMENT, "evidence.attached").size());
    }

    // ---- counting ------------------------------------------------------------------------------------------

    @Test
    void theTeamCountExcludesPlayerFilesAndTheBreakdownReportsThem() {
        givenStaffEvidence(PUNISHMENT);
        givenStaffEvidence(PUNISHMENT);
        givenStaffEvidence(PUNISHMENT);
        bind(staged(png(8, 8), "a.png").attachmentId());
        bind(staged(png(9, 9), "b.png").attachmentId());

        assertEquals(3, this.bed.service.countValid(PUNISHMENT), "the number the ban screen reads is the team's");
        assertEquals(2, this.bed.service.countPlayerSupplied(PUNISHMENT));
        EvidenceDTO.CountResponse count = assertInstanceOf(EvidenceDTO.CountResponse.class, this.bed.service.count(PUNISHMENT));
        assertEquals(3, count.count());
        assertEquals(2, count.playerSupplied());
    }

    @Test
    void aRevokedPlayerFileIsNotCountedAnymore() {
        EvidenceDTO.Staged file = staged(png(8, 8), "a.png");
        bind(file.attachmentId());
        this.bed.service.markRevoked(PUNISHMENT, STAFF_ATTRIBUTION);

        assertEquals(0, this.bed.service.countPlayerSupplied(PUNISHMENT));
    }

    @Test
    void aBoundPlayerFileIsListedWithItsProvenance() {
        bind(staged(png(8, 8), "a.png").attachmentId());

        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.list(PUNISHMENT, false, Pageable.from(0, 20), ACTOR));

        assertEquals(1, page.items().size());
        assertEquals(EvidenceProvenance.PLAYER, page.items().getFirst().provenance(), "labelled player-supplied wherever evidence is shown");
        assertEquals(EvidenceStatus.VALID, page.items().getFirst().status());
    }

    // ---- erasure -------------------------------------------------------------------------------------------

    @Test
    void deletingNamedFilesRemovesRowsAndBlobs() {
        EvidenceDTO.Staged keep = staged(png(8, 8), "keep.png");
        EvidenceDTO.Staged drop = staged(png(9, 9), "drop.png");
        bind(keep.attachmentId());
        bind(drop.attachmentId());

        assertTrue(this.bed.chatFiles.deleteFiles(PUNISHMENT, List.of(drop.attachmentId()), STAFF_ATTRIBUTION));

        assertEquals(List.of(keep.attachmentId()), this.bed.items.all().stream().map(EvidenceItemEntity::getIdentifier).toList());
        assertEquals(1, this.bed.blobs.keys().size(), "only the dropped file's blob is gone");
        assertEquals(EvidenceAuditAction.DELETED, this.bed.audit.all().getLast().getAction());
    }

    @Test
    void deletingNeverTouchesTeamEvidenceEvenWhenItsIdIsNamed() {
        EvidenceItemEntity team = givenStaffEvidence(PUNISHMENT);

        assertTrue(this.bed.chatFiles.deleteFiles(PUNISHMENT, List.of(team.getIdentifier()), STAFF_ATTRIBUTION));

        assertNotNull(this.bed.items.repository().findById(team.getIdentifier()).orElse(null), "team evidence is not a chat file");
    }

    @Test
    void theEraseOfThePlayerDeletesEveryPlayerFileButNoStaffFile() {
        EvidenceDTO.Staged player = staged(png(8, 8), "p.png");
        bind(player.attachmentId());
        EvidenceDTO staff = this.bed.chatFiles.stage(
                new ChatFileService.Stage(PUNISHMENT, ChatFiles.caseScope(PUNISHMENT), EvidenceProvenance.STAFF, STAFF_ATTRIBUTION, "s.png"),
                new ByteArrayInputStream(png(9, 9)));
        bind(assertInstanceOf(EvidenceDTO.Staged.class, staff).attachmentId());

        assertTrue(this.bed.chatFiles.deletePlayerFiles(PUNISHMENT, STAFF_ATTRIBUTION));

        assertEquals(1, this.bed.items.all().size(), "the staff file stays");
        assertEquals(EvidenceProvenance.STAFF, this.bed.items.all().getFirst().getProvenance());
        assertEquals(1, this.bed.blobs.keys().size());
    }

    @Test
    void aBlobThatCannotBeDeletedStopsTheErasureAndKeepsTheRow() {
        EvidenceDTO.Staged player = staged(png(8, 8), "p.png");
        bind(player.attachmentId());
        this.bed.blobs.fail();

        assertFalse(this.bed.chatFiles.deletePlayerFiles(PUNISHMENT, STAFF_ATTRIBUTION), "the caller must not go on as if it was done");

        assertEquals(1, this.bed.items.all().size(), "the row stays so that a retry finds the file");
    }
}
