package net.onelitefeather.blackhole.backend.evidence.service;

import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OTHER_OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceServiceTest {

    private static final UUID SHARED = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
    }

    private PunishmentEntity givenPunishment(UUID uuid) {
        String id = IdGenerator.generateId(uuid);
        PunishmentEntity punishment = new PunishmentEntity(id, STAFF, OWNER, "network", false, 1L, null, new HashMap<>());
        this.bed.punishments.given(punishment, id);
        return punishment;
    }

    private static EvidenceDTO.CreateRequest link(String punishment, String owner, String correlation) {
        return new EvidenceDTO.CreateRequest(punishment, owner, correlation, EvidenceKind.LINK, "test-system", 5L,
                "https://example.org/replay/1", Map.of("description", "d"), STAFF);
    }

    private EvidenceDTO.Response attached(EvidenceDTO result) {
        return assertInstanceOf(EvidenceDTO.Response.class, result, () -> String.valueOf(result));
    }

    private static void assertError(EvidenceDTO result, EvidenceErrorKind kind) {
        EvidenceDTO.Error error = assertInstanceOf(EvidenceDTO.Error.class, result, () -> "expected " + kind + " but got " + result);
        assertEquals(kind, error.kind(), error.errorMessage());
    }

    @Test
    void evidenceIsAttachedByTheFullPunishmentIdentifier() {
        PunishmentEntity punishment = givenPunishment(SHARED);

        EvidenceDTO.Response response = attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));

        assertEquals(punishment.getIdentifier(), response.punishmentIdentifier());
        assertEquals(OWNER, response.owner(), "the owner comes from the punishment, not from the caller");
        assertEquals(EvidenceStatus.VALID, response.status());
        assertNull(response.correlationId());
    }

    @Test
    void evidenceIsAttachedByAShortIdThatMatchesOnePunishment() {
        PunishmentEntity punishment = givenPunishment(SHARED);

        EvidenceDTO.Response response = attached(this.bed.service.attach(link("#a1b2c3d4", null, null)));

        assertEquals(punishment.getIdentifier(), response.punishmentIdentifier());
    }

    @Test
    void aShortIdThatMatchesNothingIsNotFoundAndStoresNothing() {
        givenPunishment(SHARED);

        assertError(this.bed.service.attach(link("#00000000", null, null)), EvidenceErrorKind.NOT_FOUND);
        assertTrue(this.bed.items.all().isEmpty(), "nothing is stored");
    }

    @Test
    void aShortIdThatMatchesSeveralPunishmentsIsAConflictListingTheCandidates() {
        PunishmentEntity first = givenPunishment(SHARED);
        PunishmentEntity second = givenPunishment(UUID.fromString("a1b2c3d4-ffff-4000-8000-000000000002"));

        EvidenceDTO result = this.bed.service.attach(link("#A1B2C3D4", null, null));

        assertError(result, EvidenceErrorKind.CONFLICT);
        String message = ((EvidenceDTO.Error) result).errorMessage();
        assertTrue(message.contains(first.getIdentifier()) && message.contains(second.getIdentifier()), message);
        assertTrue(this.bed.items.all().isEmpty(), "nothing is stored");
    }

    @Test
    void anUnknownPunishmentReferenceIsNotFound() {
        assertError(this.bed.service.attach(link("not-a-punishment", null, null)), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void aPunishmentTogetherWithAnOwnerIsRejectedBecauseExactlyOneTargetIsRequired() {
        PunishmentEntity punishment = givenPunishment(SHARED);

        assertError(this.bed.service.attach(link(punishment.getIdentifier(), OWNER, "c1")), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void noTargetAtAllIsRejected() {
        assertError(this.bed.service.attach(link(null, null, null)), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void anOwnerWithoutACorrelationIdIsRejected() {
        assertError(this.bed.service.attach(link(null, OWNER, null)), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void aCorrelationIdWithoutAnOwnerIsRejected() {
        assertError(this.bed.service.attach(link(null, null, "c1")), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void anOwnerThatIsNotAHashIsRejected() {
        assertError(this.bed.service.attach(link(null, "player-uuid", "c1")), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void evidenceBeforeThePunishmentIsStoredPendingForTheOwner() {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertNull(response.punishmentIdentifier(), "a pending item belongs to no punishment yet");
        assertEquals("c1", response.correlationId());
        assertEquals(OWNER, response.owner());
    }

    @Test
    void aMissingSourceSystemIsRejected() {
        EvidenceDTO.CreateRequest request = new EvidenceDTO.CreateRequest(null, OWNER, "c1", EvidenceKind.LINK, " ", null,
                "https://example.org/", null, null);

        assertError(this.bed.service.attach(request), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void aPayloadThePayloadValidatorRejectsIsNotStored() {
        EvidenceDTO.CreateRequest request = new EvidenceDTO.CreateRequest(null, OWNER, "c1", EvidenceKind.LINK, "test", null,
                "javascript:alert(1)", null, null);

        assertError(this.bed.service.attach(request), EvidenceErrorKind.VALIDATION);
        assertTrue(this.bed.items.all().isEmpty(), "nothing is stored");
    }

    @Test
    void aPunishmentCanHaveManyItems() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        for (int i = 0; i < 3; i++) {
            attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));
            this.bed.clock.advance(java.time.Duration.ofSeconds(1));
        }

        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.list(punishment.getIdentifier(), false, Pageable.from(0, 20), STAFF));

        assertEquals(3, page.items().size());
        assertEquals(3, page.totalSize());
    }

    @Test
    void listingIsPaged() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        for (int i = 0; i < 3; i++) {
            attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));
            this.bed.clock.advance(java.time.Duration.ofSeconds(1));
        }

        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.list(punishment.getIdentifier(), false, Pageable.from(1, 2), STAFF));

        assertEquals(1, page.items().size(), "the second page of two holds the third item");
        assertEquals(2, page.totalPages());
    }

    private EvidenceItemEntity givenInactive(PunishmentEntity punishment, EvidenceStatus status) {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));
        EvidenceItemEntity item = this.bed.items.all().stream().filter(i -> i.getIdentifier().equals(response.identifier())).findFirst().orElseThrow();
        item.markStatus(status, 99L, null);
        return item;
    }

    @Test
    void liftedAndRevokedItemsAreHiddenFromTheDefaultList() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));
        givenInactive(punishment, EvidenceStatus.LIFTED);
        givenInactive(punishment, EvidenceStatus.REVOKED);

        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.list(punishment.getIdentifier(), false, Pageable.from(0, 20), STAFF));

        assertEquals(1, page.items().size());
        assertEquals(EvidenceStatus.VALID, page.items().getFirst().status());
    }

    @Test
    void includingInactiveItemsReturnsThemWithTheirStatus() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        givenInactive(punishment, EvidenceStatus.LIFTED);

        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.list(punishment.getIdentifier(), true, Pageable.from(0, 20), STAFF));

        assertEquals(1, page.items().size());
        assertEquals(EvidenceStatus.LIFTED, page.items().getFirst().status());
        assertEquals(99L, page.items().getFirst().statusChangedAt());
    }

    @Test
    void liftedAndRevokedItemsAreNotCounted() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));
        attached(this.bed.service.attach(link(punishment.getIdentifier(), null, null)));
        givenInactive(punishment, EvidenceStatus.LIFTED);
        givenInactive(punishment, EvidenceStatus.REVOKED);

        EvidenceDTO.CountResponse count = assertInstanceOf(EvidenceDTO.CountResponse.class, this.bed.service.count(punishment.getIdentifier()));

        assertEquals(2, count.count());
    }

    @Test
    void theCountOfAnUnknownPunishmentIsNotFound() {
        assertError(this.bed.service.count("missing"), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void listingByOwnerRequiresAHash() {
        assertError(this.bed.service.listByOwner("name", false, Pageable.from(0, 20), STAFF), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void listingByOwnerReturnsOnlyThatOwnersItems() {
        attached(this.bed.service.attach(link(null, OWNER, "c1")));
        attached(this.bed.service.attach(link(null, OTHER_OWNER, "c1")));

        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.bed.service.listByOwner(OWNER, false, Pageable.from(0, 20), STAFF));

        assertEquals(1, page.items().size());
        assertEquals(OWNER, page.items().getFirst().owner());
    }

    @Test
    void anUnknownEvidenceIdIsNotFound() {
        assertError(this.bed.service.get(UUID.randomUUID(), STAFF), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void deletingWithoutAReasonIsRejectedAndKeepsTheItem() {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertError(this.bed.service.delete(response.identifier(), null, null, STAFF), EvidenceErrorKind.VALIDATION);
        assertEquals(1, this.bed.items.all().size(), "the item still exists");
    }

    @Test
    void deletingWithAnUnknownReasonIsRejectedAndKeepsTheItem() {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertError(this.bed.service.delete(response.identifier(), "BORED", null, STAFF), EvidenceErrorKind.VALIDATION);
        assertEquals(1, this.bed.items.all().size());
    }

    @Test
    void deletingWithATooLongNoteIsRejected() {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertError(this.bed.service.delete(response.identifier(), "OTHER", "x".repeat(501), STAFF), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void deletingWithAReasonRemovesTheItem() {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(null, OWNER, "c1")));

        EvidenceDTO.DeleteResponse deleted = assertInstanceOf(EvidenceDTO.DeleteResponse.class,
                this.bed.service.delete(response.identifier(), "wrong_evidence", "typo", STAFF));

        assertEquals(1, deleted.deleted());
        assertTrue(this.bed.items.all().isEmpty());
    }

    @Test
    void deletingAnUnknownItemIsNotFound() {
        assertError(this.bed.service.delete(UUID.randomUUID(), "OTHER", null, STAFF), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void erasingAnOwnerDeletesOnlyThatOwnersItemsAndFiles() throws IOException {
        EvidenceDTO.Response file = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));
        attached(this.bed.service.attach(link(null, OWNER, "c2")));
        attached(this.bed.service.attach(link(null, OTHER_OWNER, "c1")));

        EvidenceDTO.DeleteResponse deleted = assertInstanceOf(EvidenceDTO.DeleteResponse.class,
                this.bed.service.deleteByOwner(OWNER, "ERASURE_REQUEST", null, STAFF));

        assertEquals(2, deleted.deleted());
        assertEquals(1, this.bed.items.all().size());
        assertEquals(OTHER_OWNER, this.bed.items.all().getFirst().getOwner());
        assertTrue(this.bed.blobs.keys().isEmpty(), "the file of " + file.identifier() + " is gone");
    }

    @Test
    void erasingWithoutAReasonDeletesNothing() {
        attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertError(this.bed.service.deleteByOwner(OWNER, "", null, STAFF), EvidenceErrorKind.VALIDATION);
        assertEquals(1, this.bed.items.all().size());
    }

    @Test
    void erasingKeepsTheRowWhenItsFileCannotBeDeleted() throws IOException {
        attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));
        this.bed.blobs.fail();

        assertError(this.bed.service.deleteByOwner(OWNER, "ERASURE_REQUEST", null, STAFF), EvidenceErrorKind.UNAVAILABLE);
        assertEquals(1, this.bed.items.all().size(), "the row stays so the deletion can be retried");
    }

    @Test
    void claimingAttachesThePendingItemsOfTheOwnerAndCorrelation() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        attached(this.bed.service.attach(link(null, OWNER, "c1")));
        attached(this.bed.service.attach(link(null, OWNER, "c1")));

        int claimed = this.bed.service.claimPending(OWNER, "c1", punishment.getIdentifier());

        assertEquals(2, claimed);
        assertEquals(2, this.bed.service.countValid(punishment.getIdentifier()));
        assertTrue(this.bed.items.all().stream().allMatch(item -> item.getCorrelationId() == null), "the correlation id is spent");
    }

    @Test
    void claimingIgnoresAnotherOwnersAndAnotherCorrelationsItems() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        attached(this.bed.service.attach(link(null, OTHER_OWNER, "c1")));
        attached(this.bed.service.attach(link(null, OWNER, "c2")));

        assertEquals(0, this.bed.service.claimPending(OWNER, "c1", punishment.getIdentifier()));
        assertTrue(this.bed.items.all().stream().allMatch(EvidenceItemEntity::isPending), "nothing was claimed");
    }

    @Test
    void claimingWithoutACorrelationIdClaimsNothing() {
        PunishmentEntity punishment = givenPunishment(SHARED);
        attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertEquals(0, this.bed.service.claimPending(OWNER, null, punishment.getIdentifier()));
    }

    private EvidenceDTO upload(String owner, String correlation, byte[] content) {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, owner, correlation, "test-system", null, null, STAFF, "shot.png");
        return this.bed.service.attachFile(request, new ByteArrayInputStream(content));
    }

    @Test
    void anUploadedImageIsStoredUnderAGeneratedKeyWithItsMetadata() throws IOException {
        EvidenceDTO.Response response = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));

        assertEquals(EvidenceKind.FILE, response.kind());
        assertEquals("image/png", response.file().contentType());
        assertEquals("shot.png", response.file().name());
        assertEquals(1, this.bed.blobs.keys().size());
        String key = this.bed.blobs.keys().iterator().next();
        assertEquals(response.file().size(), this.bed.blobs.bytes(key).length);
        assertNotNull(response.file().sha256());
    }

    @Test
    void anUploadKeepsAnOptionalDescription() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, OWNER, "c1", "test", null, "the hacker", STAFF, "a.png");

        EvidenceDTO.Response response = attached(this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8))));

        assertEquals("the hacker", response.payload().get("description"));
    }

    @Test
    void aDescriptionWithAnIpAddressIsRefusedAndNothingIsStored() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, OWNER, "c1", "test", null, "from 203.0.113.5", STAFF, "a.png");

        assertError(this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8))), EvidenceErrorKind.UNPROCESSABLE);
        assertTrue(this.bed.blobs.keys().isEmpty());
        assertTrue(this.bed.items.all().isEmpty());
    }

    @Test
    void aFlaggedUploadIsRejectedAndLeavesNoBlobNoRowAndNoTemporaryFile() throws IOException {
        this.bed.scanner.verdict = new EvidenceScanner.Infected("Eicar");

        assertError(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)), EvidenceErrorKind.UNPROCESSABLE);

        assertTrue(this.bed.blobs.keys().isEmpty());
        assertTrue(this.bed.items.all().isEmpty());
        try (Stream<Path> left = Files.list(this.spool)) {
            assertEquals(0, left.count(), "the spooled upload is deleted");
        }
    }

    @Test
    void aFileOfAnUnsupportedTypeIsRejectedWith415() {
        assertError(upload(OWNER, "c1", "MZ not an image".getBytes(StandardCharsets.US_ASCII)), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
        assertTrue(this.bed.blobs.keys().isEmpty());
    }

    @Test
    void anUploadIsRejectedWhenTheStoreIsUnavailableAndLeavesNoRow() throws IOException {
        this.bed.blobs.fail();

        assertError(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)), EvidenceErrorKind.UNAVAILABLE);
        assertTrue(this.bed.items.all().isEmpty());
    }

    @Test
    void anUploadWithoutATargetIsRejectedBeforeAnyBytesAreRead() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, null, null, "test", null, null, STAFF, "a.png");

        assertError(this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8))), EvidenceErrorKind.VALIDATION);
    }

    @Test
    void contentIsServedWhole() throws IOException {
        EvidenceDTO.Response response = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));

        EvidenceDTO.Download download = assertInstanceOf(EvidenceDTO.Download.class, this.bed.service.content(response.identifier(), null, STAFF));

        assertEquals(response.file().size(), download.length());
        assertEquals(false, download.partial());
        assertEquals("image/png", download.contentType());
        assertEquals(response.file().size(), download.stream().readAllBytes().length);
    }

    @Test
    void contentHonoursARangeAndReportsTheWholeSize() throws IOException {
        EvidenceDTO.Response response = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));
        String key = this.bed.blobs.keys().iterator().next();
        byte[] stored = this.bed.blobs.bytes(key);

        EvidenceDTO.Download download = assertInstanceOf(EvidenceDTO.Download.class, this.bed.service.content(response.identifier(), "bytes=2-5", STAFF));

        assertEquals(true, download.partial());
        assertEquals(4, download.length());
        assertEquals(stored.length, download.total());
        assertEquals(2, download.start());
        assertEquals(5, download.end());
        assertEquals(java.util.Arrays.toString(java.util.Arrays.copyOfRange(stored, 2, 6)), java.util.Arrays.toString(download.stream().readAllBytes()));
    }

    @Test
    void aRangeBehindTheEndOfTheFileIsOutOfRange() throws IOException {
        EvidenceDTO.Response response = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));

        EvidenceDTO.OutOfRange outOfRange = assertInstanceOf(EvidenceDTO.OutOfRange.class,
                this.bed.service.content(response.identifier(), "bytes=999999-", STAFF));

        assertEquals(response.file().size(), outOfRange.size());
    }

    @Test
    void contentOfAnItemWithoutAFileIsNotFound() {
        EvidenceDTO.Response response = attached(this.bed.service.attach(link(null, OWNER, "c1")));

        assertError(this.bed.service.content(response.identifier(), null, STAFF), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void contentWhoseBlobIsMissingIsNotFound() throws IOException {
        EvidenceDTO.Response response = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));
        this.bed.blobs.keys().clear();

        assertError(this.bed.service.content(response.identifier(), null, STAFF), EvidenceErrorKind.NOT_FOUND);
    }

    @Test
    void contentIsUnavailableWhenTheStoreFails() throws IOException {
        EvidenceDTO.Response response = attached(upload(OWNER, "c1", EvidenceFixtures.png(8, 8)));
        this.bed.blobs.fail();

        assertError(this.bed.service.content(response.identifier(), null, STAFF), EvidenceErrorKind.UNAVAILABLE);
    }
}
