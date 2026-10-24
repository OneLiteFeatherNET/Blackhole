package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.casetimeline.service.ParticipantErasure;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.FakeChatFiles;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files on messages: the claim, the payload entry, idempotency, the caps and the erasure, over in-memory stores and a fake file side. */
class CaseConversationFilesTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final UUID APPEAL = UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a1");
    private static final Actor ALICE = new Actor(UUID.nameUUIDFromBytes("alice".getBytes()), "Alice", ActorKind.HUMAN, false);
    private static final Actor ADMIN = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true);
    private static final String CASE_SCOPE = ChatFiles.caseScope(ID);
    private static final String APPEAL_SCOPE = ChatFiles.appealScope(APPEAL);

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore store = new FakeCaseStore(this.log);
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final FakeChatFiles files = new FakeChatFiles();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream().filter(p -> args[0].equals(p.getShortId())).toList())
                    .build();
    private final BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
    private final CaseConversationService service = new CaseConversationService(
            this.punishments.repository(), this.store, this.store, this.log, this.events, this.clock, this.banIds.service,
            new RecordingProtocols(), ParticipantErasure.NONE, this.files);

    private void givenPunishment() {
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), ID);
        this.store.ensureCase(ID, 1L);
    }

    private static CaseConversationDTO.MessageRequest send(String text, UUID... ids) {
        return new CaseConversationDTO.MessageRequest(text, List.of(ids));
    }

    private CaseEventPayload.MessageAdded stored() {
        return assertInstanceOf(CaseEventPayload.MessageAdded.class, this.log.ofType(ID, "message.added").getLast().payload());
    }

    private static CaseErrorKind kindOf(CaseConversationDTO result) {
        return assertInstanceOf(CaseConversationDTO.Error.class, result).kind();
    }

    // ---- staff messages with files -------------------------------------------------------------------------

    @Test
    void aStaffMessageSendsItsStagedFilesAndClaimsThemInTheSameStatement() {
        givenPunishment();
        UUID first = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);
        UUID second = this.files.stage(CASE_SCOPE, "other.png", EvidenceProvenance.STAFF);

        CaseConversationDTO result = this.service.postMessage(ID, send("See the screenshots.", first, second), ALICE, null);

        assertFalse(assertInstanceOf(CaseConversationDTO.MessageResponse.class, result).repeated());
        assertEquals(List.of(new CaseChanges.Claim(List.of(first, second), CASE_SCOPE)), this.store.claims(), "the claim travels with the message");
        assertEquals(List.of(first, second), this.files.bound(), "the files become case evidence");
    }

    @Test
    void thePayloadEntryHoldsIdNameTypeSizeAndProvenanceOnly() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);

        this.service.postMessage(ID, send("x", id), ALICE, null);

        Map<String, Object> entry = (Map<String, Object>) ((List<?>) this.log.ofType(ID, "message.added").getLast().body().get("attachments")).getFirst();
        assertEquals(id.toString(), entry.get("attachmentId"));
        assertEquals("shot.png", entry.get("name"));
        assertEquals("image/png", entry.get("contentType"));
        assertEquals("STAFF", entry.get("provenance"));
        assertEquals("file", entry.get("kind"));
        assertEquals(Set.of("kind", "attachmentId", "name", "contentType", "size", "width", "height", "provenance"), entry.keySet(),
                "no storage key, no hash, no bytes in a payload");
    }

    @Test
    void aMessageThatIsOnlyFilesIsAllowedAndHasEmptyText() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);

        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postMessage(ID, send(null, id), ALICE, null));

        assertEquals("", stored().text(), "the text is empty, not missing: a missing text means removed");
        assertEquals(1, stored().attachments().size());
    }

    @Test
    void aMessageWithNeitherTextNorFilesIsRefused() {
        givenPunishment();

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, send(null), ALICE, null)));
        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, send("   "), ALICE, null)));
        assertTrue(this.log.ofType(ID, "message.added").isEmpty());
    }

    @Test
    void theTextOfAMessageWithFilesIsStillChecked() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);

        CaseConversationDTO result = this.service.postMessage(ID, send("ip 192.168.1.20", id), ALICE, null);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(result));
        assertTrue(this.store.claims().isEmpty(), "nothing is claimed for a refused message");
    }

    @Test
    void sixFilesAreRefusedAndNothingIsClaimed() {
        givenPunishment();
        UUID[] ids = new UUID[6];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = this.files.stage(CASE_SCOPE, "f" + i + ".png", EvidenceProvenance.STAFF);
        }

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, send("x", ids), ALICE, null)));

        assertTrue(this.store.claims().isEmpty());
        assertTrue(this.files.bound().isEmpty());
        assertTrue(this.log.ofType(ID, "message.added").isEmpty());
    }

    @Test
    void fiveFilesAreAllowed() {
        givenPunishment();
        UUID[] ids = new UUID[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = this.files.stage(CASE_SCOPE, "f" + i + ".png", EvidenceProvenance.STAFF);
        }

        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postMessage(ID, send("x", ids), ALICE, null));
    }

    @Test
    void aFileOfAnotherConversationIsRefused() {
        givenPunishment();
        UUID foreign = this.files.stage(ChatFiles.caseScope("CCCCCCCCCCCCCCCCCCCCCC"), "x.png", EvidenceProvenance.STAFF);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, send("x", foreign), ALICE, null)));

        assertTrue(this.store.claims().isEmpty());
        assertTrue(this.log.ofType(ID, "message.added").isEmpty());
    }

    @Test
    void anUnknownFileIsRefusedWithTheSameAnswerAsAForeignOne() {
        givenPunishment();
        UUID foreign = this.files.stage(ChatFiles.caseScope("CCCCCCCCCCCCCCCCCCCCCC"), "x.png", EvidenceProvenance.STAFF);

        String unknown = assertInstanceOf(CaseConversationDTO.Error.class, this.service.postMessage(ID, send("x", UUID.randomUUID()), ALICE, null)).errorMessage();
        String other = assertInstanceOf(CaseConversationDTO.Error.class, this.service.postMessage(ID, send("x", foreign), ALICE, null)).errorMessage();

        assertEquals(unknown, other, "nothing to probe");
    }

    @Test
    void aFileNamedTwiceIsRefused() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, send("x", id, id), ALICE, null)));
    }

    @Test
    void aFileThatWasSentAlreadyCannotBeSentAgain() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);
        this.service.postMessage(ID, send("first", id), ALICE, null);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, send("second", id), ALICE, null)));

        assertEquals(1, this.log.ofType(ID, "message.added").size());
    }

    @Test
    void aRepeatWithTheSameIdempotencyKeyChangesNothing() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);
        CaseConversationDTO.MessageResponse first = assertInstanceOf(CaseConversationDTO.MessageResponse.class,
                this.service.postMessage(ID, send("x", id), ALICE, "key-1"));

        CaseConversationDTO.MessageResponse second = assertInstanceOf(CaseConversationDTO.MessageResponse.class,
                this.service.postMessage(ID, send("x", id), ALICE, "key-1"));

        assertFalse(first.repeated());
        assertTrue(second.repeated(), "the second answer says so");
        assertEquals(first.messageId(), second.messageId());
        assertEquals(first.seq(), second.seq());
        assertEquals(1, this.log.ofType(ID, "message.added").size(), "one message");
        assertEquals(1, this.store.claims().size(), "one claim");
        assertEquals(List.of(id), this.files.bound(), "recorded as evidence once");
    }

    @Test
    void losingTheRaceForAFileIsAConflictAndStoresNoMessage() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "shot.png", EvidenceProvenance.STAFF);
        this.store.loseNextClaims(1);

        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.postMessage(ID, send("x", id), ALICE, null)));

        assertTrue(this.log.ofType(ID, "message.added").isEmpty());
        assertTrue(this.files.bound().isEmpty());
    }

    @Test
    void theDomainEventCarriesTheCountAndNoFileFacts() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "secret-name.png", EvidenceProvenance.STAFF);

        this.service.postMessage(ID, send("x", id), ALICE, null);

        Map<String, Object> payload = this.events.eventsOfType("case.message_added").getFirst().payload();
        assertEquals(1, payload.get("attachments"), "only how many");
        assertFalse(payload.toString().contains("secret-name"), "no file name in an event");
        assertFalse(payload.toString().contains(id.toString()), "no file id in an event");
        assertFalse(payload.containsKey("text"));
    }

    // ---- player messages with files ------------------------------------------------------------------------

    @Test
    void aPlayerMessageClaimsTheFilesStagedForItsAppeal() {
        givenPunishment();
        UUID id = this.files.stage(APPEAL_SCOPE, "proof.png", EvidenceProvenance.PLAYER);

        CaseConversationDTO result = this.service.postPlayerMessage(ID, APPEAL, "Here is my proof.", null, List.of(id));

        assertInstanceOf(CaseConversationDTO.MessageResponse.class, result);
        assertEquals(List.of(new CaseChanges.Claim(List.of(id), APPEAL_SCOPE)), this.store.claims());
        CaseEventRecord message = this.log.ofType(ID, "message.added").getLast();
        assertEquals(ActorTrust.PLAYER, message.actorTrust());
        assertEquals("PLAYER", stored().attachments().getFirst().provenance());
        assertEquals(id.toString(), stored().attachments().getFirst().attachmentId());
    }

    @Test
    void aPlayerCannotSendAFileStagedForAnotherAppeal() {
        givenPunishment();
        UUID foreign = this.files.stage(ChatFiles.appealScope(UUID.randomUUID()), "x.png", EvidenceProvenance.PLAYER);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postPlayerMessage(ID, APPEAL, "x", null, List.of(foreign))));

        assertTrue(this.log.ofType(ID, "message.added").isEmpty());
    }

    @Test
    void aPlayerCannotSendAStaffFile() {
        givenPunishment();
        UUID staffFile = this.files.stage(CASE_SCOPE, "x.png", EvidenceProvenance.STAFF);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postPlayerMessage(ID, APPEAL, "x", null, List.of(staffFile))));
    }

    @Test
    void aPlayerMessageThatIsOnlyAFileMovesAWaitingCase() {
        givenPunishment();
        this.store.update(ID, 0, 2L, CaseActor.system(), CaseMeta.defaults(), new CaseMeta("WAITING", null, "NORMAL", List.of()));
        UUID id = this.files.stage(APPEAL_SCOPE, "proof.png", EvidenceProvenance.PLAYER);

        this.service.postPlayerMessage(ID, APPEAL, null, null, List.of(id));

        assertEquals("IN_REVIEW", this.store.meta(ID).status());
        assertEquals("", stored().text());
    }

    @Test
    void filesNeedAnAppeal() {
        givenPunishment();
        UUID id = this.files.stage(APPEAL_SCOPE, "proof.png", EvidenceProvenance.PLAYER);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postPlayerMessage(ID, null, "x", null, List.of(id))));
    }

    @Test
    void aFrozenCaseTakesNoFilesEither() {
        givenPunishment();
        this.store.markBanIdLeaked(ID, BanIdLeakReason.OTHER, BanIdLeakSource.STAFF, 3L, CaseActor.system());
        UUID id = this.files.stage(APPEAL_SCOPE, "proof.png", EvidenceProvenance.PLAYER);

        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.postPlayerMessage(ID, APPEAL, "x", null, List.of(id))));

        assertTrue(this.store.claims().isEmpty());
    }

    // ---- erasure -------------------------------------------------------------------------------------------

    @Test
    void redactingAMessageDeletesItsFilesFirst() {
        givenPunishment();
        UUID first = this.files.stage(CASE_SCOPE, "a.png", EvidenceProvenance.STAFF);
        UUID second = this.files.stage(CASE_SCOPE, "b.png", EvidenceProvenance.STAFF);
        String messageId = assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postMessage(ID, send("x", first, second), ALICE, null)).messageId();

        CaseConversationDTO result = this.service.redactMessage(ID, UUID.fromString(messageId),
                new CaseConversationDTO.RedactRequest(NoteRedactionReason.PERSONAL_DATA), ADMIN);

        assertInstanceOf(CaseConversationDTO.RedactResponse.class, result);
        assertEquals(List.of(first, second), this.files.deleted(), "both files of the message are deleted");
        assertNull(this.log.ofType(ID, "message.added").getFirst().body().get("attachments"), "and the payload forgot them");
    }

    @Test
    void aRedactionStopsWhenAFileCannotBeDeleted() {
        givenPunishment();
        UUID id = this.files.stage(CASE_SCOPE, "a.png", EvidenceProvenance.STAFF);
        String messageId = assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postMessage(ID, send("x", id), ALICE, null)).messageId();
        this.files.failDeletes();

        CaseConversationDTO result = this.service.redactMessage(ID, UUID.fromString(messageId),
                new CaseConversationDTO.RedactRequest(NoteRedactionReason.PERSONAL_DATA), ADMIN);

        assertEquals(CaseErrorKind.UNAVAILABLE, kindOf(result));
        assertEquals("x", stored().text(), "nothing was redacted: a retry can finish the job");
        assertTrue(this.log.ofType(ID, "message.redacted").isEmpty());
    }

    @Test
    void erasingThePlayerDeletesEveryPlayerFile() {
        givenPunishment();
        UUID id = this.files.stage(APPEAL_SCOPE, "proof.png", EvidenceProvenance.PLAYER);
        this.service.postPlayerMessage(ID, APPEAL, "mine", null, List.of(id));

        CaseConversationDTO result = this.service.erasePlayerMessages(ID, new CaseConversationDTO.RedactRequest(NoteRedactionReason.ERASURE_REQUEST), ADMIN);

        assertEquals(1, assertInstanceOf(CaseConversationDTO.EraseResponse.class, result).redactedMessages());
        assertTrue(this.files.playerFilesDeleted());
    }

    @Test
    void theEraseStopsWhenAFileCannotBeDeleted() {
        givenPunishment();
        UUID id = this.files.stage(APPEAL_SCOPE, "proof.png", EvidenceProvenance.PLAYER);
        this.service.postPlayerMessage(ID, APPEAL, "mine", null, List.of(id));
        this.files.failDeletes();

        CaseConversationDTO result = this.service.erasePlayerMessages(ID, new CaseConversationDTO.RedactRequest(NoteRedactionReason.ERASURE_REQUEST), ADMIN);

        assertEquals(CaseErrorKind.UNAVAILABLE, kindOf(result));
        assertEquals("mine", stored().text(), "the words stay until the files can go too");
    }
}
