package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditEntry;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The case rules of the service over in-memory stores: validation, versions, authorship, redaction and events. No database. */
class CaseServiceTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final Actor ALICE = new Actor(UUID.nameUUIDFromBytes("alice".getBytes()), "Alice", ActorKind.HUMAN, false);
    private static final Actor BOB = new Actor(UUID.nameUUIDFromBytes("bob".getBytes()), "Bob", ActorKind.HUMAN, false);
    private static final Actor ADMIN = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true);
    private static final UUID SERVICE_PRINCIPAL = UUID.nameUUIDFromBytes("service".getBytes());

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore store = new FakeCaseStore(log);
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream().filter(p -> args[0].equals(p.getShortId())).toList())
                    .build();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> staff =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final StaffAuditRecorder audit = new StaffAuditRecorder(this.auditStore,
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), new AuditPayloadGuard(), this.clock, new SimpleMeterRegistry());
    private final CaseService service = new CaseService(
            this.punishments.repository(), this.staff.repository(), this.store, this.store, this.log, this.events, this.clock,
            this.banIds.service, new MessageContent("onelitefeather.net"), this.audit);

    private void givenPunishment(String identifier) {
        this.punishments.given(new PunishmentEntity(identifier, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), identifier);
    }

    private void givenStaff(UUID oid, ActorKind kind) {
        this.staff.given(new StaffActorEntity(oid, kind, "Name", 1L), oid);
    }

    private static CaseDTO.UpdateRequest patch(long version, CaseStatus status, UUID assignee, CasePriority priority, List<String> tags) {
        return new CaseDTO.UpdateRequest(version, status, assignee, false, priority, tags);
    }

    private static CaseErrorKind kindOf(CaseDTO result) {
        return assertInstanceOf(CaseDTO.Error.class, result).kind();
    }

    private String addNote(Actor by, String text) {
        return assertInstanceOf(CaseDTO.NoteResponse.class, this.service.addNote(ID, new CaseDTO.NoteRequest(text), by)).noteId();
    }

    @Test
    void aStatusChangeUpdatesTheCaseAndAppendsItsEvent() {
        givenPunishment(ID);

        CaseDTO result = this.service.update(ID, patch(0, CaseStatus.IN_REVIEW, null, null, null), ALICE);

        assertEquals(CaseStatus.IN_REVIEW, assertInstanceOf(CaseDTO.Response.class, result).status());
        assertEquals(List.of(new CaseEventPayload.StatusChanged("OPEN", "IN_REVIEW")), this.log.all().stream().map(CaseEventRecord::payload).toList());
        assertEquals(ActorTrust.VERIFIED, this.log.all().getFirst().actorTrust(), "a web action is verified");
        assertEquals(ALICE.oid(), this.log.all().getFirst().actorOid());
    }

    @Test
    void aStaleVersionAnswers409AndChangesNothing() {
        givenPunishment(ID);
        this.service.update(ID, patch(0, CaseStatus.IN_REVIEW, null, null, null), ALICE);

        CaseDTO result = this.service.update(ID, patch(0, CaseStatus.CLOSED, null, null, null), BOB);

        assertEquals(CaseErrorKind.CONFLICT, kindOf(result));
        assertEquals("IN_REVIEW", this.store.meta(ID).status(), "the stale write must not apply");
        assertEquals(1, this.log.all().size(), "and must not append an event");
    }

    @Test
    void aVersionThatMovesBetweenReadAndWriteAnswers409() {
        givenPunishment(ID);
        this.store.ensureCase(ID, 1);
        CaseReader racingReader = new CaseReader() {
            @Override
            public Optional<CaseRow> find(String id) {
                Optional<CaseRow> row = store.find(id);
                store.bumpVersion(id);
                return row;
            }

            @Override
            public List<Related> related(String owner, String excluded, long now, int limit) {
                return List.of();
            }

            @Override
            public List<Entry> timeline(TimelineQuery query) {
                return List.of();
            }

            @Override
            public List<CaseEventRecord> subject(String id, String subjectId) {
                return List.of();
            }

            @Override
            public ListPage list(ListQuery query, long now) {
                return new ListPage(List.of(), 0);
            }
        };
        CaseService racing = new CaseService(this.punishments.repository(), this.staff.repository(), racingReader, this.store, this.log,
                this.events, this.clock, this.banIds.service, new MessageContent("onelitefeather.net"), this.audit);

        CaseDTO result = racing.update(ID, patch(0, CaseStatus.CLOSED, null, null, null), ALICE);

        assertEquals(CaseErrorKind.CONFLICT, kindOf(result), "the statement itself refuses a version that moved");
        assertTrue(this.log.all().isEmpty(), "no event for a refused write");
    }

    @Test
    void anUnchangedValueAppendsNothing() {
        givenPunishment(ID);

        CaseDTO result = this.service.update(ID, patch(0, null, null, CasePriority.NORMAL, List.of()), ALICE);

        assertInstanceOf(CaseDTO.Response.class, result);
        assertTrue(this.log.all().isEmpty(), "NORMAL is already the priority");
        assertFalse(this.store.hasCase(ID), "and no case row is created for nothing");
    }

    @Test
    void severalChangesAppendOneEventEachInOneUpdate() {
        givenPunishment(ID);

        this.service.update(ID, patch(0, CaseStatus.WAITING, null, CasePriority.HIGH, List.of("b", "a")), ALICE);

        assertEquals(List.of("case.status_changed", "case.priority_changed", "case.tags_changed"),
                this.log.all().stream().map(CaseEventRecord::type).toList());
        assertEquals(List.of("a", "b"), this.store.meta(ID).tags(), "tags are stored sorted");
    }

    @Test
    void anUnknownAssigneeAnswers422() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.UNPROCESSABLE, kindOf(this.service.update(ID, patch(0, null, UUID.randomUUID(), null, null), ALICE)));
    }

    @Test
    void aServicePrincipalCannotBeAssigned() {
        givenPunishment(ID);
        givenStaff(SERVICE_PRINCIPAL, ActorKind.SERVICE);

        assertEquals(CaseErrorKind.UNPROCESSABLE, kindOf(this.service.update(ID, patch(0, null, SERVICE_PRINCIPAL, null, null), ALICE)),
                "only a human staff actor may be assigned");
    }

    @Test
    void aKnownHumanCanBeAssignedAndUnassigned() {
        givenPunishment(ID);
        givenStaff(BOB.oid(), ActorKind.HUMAN);

        this.service.update(ID, patch(0, null, BOB.oid(), null, null), ALICE);
        assertEquals(BOB.oid(), this.store.meta(ID).assignee());
        this.service.update(ID, new CaseDTO.UpdateRequest(1L, null, null, true, null, null), ALICE);

        assertNull(this.store.meta(ID).assignee(), "clearAssignee removes the assignee");
        assertEquals(List.of("case.assignee_changed", "case.assignee_changed"), this.log.all().stream().map(CaseEventRecord::type).toList());
    }

    @Test
    void elevenTagsAreRejected() {
        givenPunishment(ID);
        List<String> tags = IntStream.range(0, 11).mapToObj(i -> "tag-" + i).toList();

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.update(ID, patch(0, null, null, null, tags), ALICE)));
        assertTrue(this.log.all().isEmpty(), "nothing changes");
    }

    @Test
    void aTagThatIsNoSlugIsRejected() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.update(ID, patch(0, null, null, null, List.of("Bad Tag!")), ALICE)));
    }

    @Test
    void aNoteAddedRecordsOneCaseNoteAddEntryNamedByShortIdOnly() {
        givenPunishment(ID);

        addNote(ALICE, "seen twice in a week");

        assertEquals(1, this.auditStore.rows().size(), "exactly one audit entry for the note");
        StaffAuditEntry row = this.auditStore.rows().getFirst();
        assertEquals("case.note.add", row.action());
        assertEquals("CASE", row.targetKind());
        assertEquals(PunishmentShortId.storageKey(ID), row.targetRef(), "the case is named by its short id");
        assertFalse(row.targetRef().contains(ID), "the audit entry never carries the punishment identifier");
    }

    @Test
    void aNoteIsStoredAsStaffText() {
        givenPunishment(ID);

        addNote(ALICE, "seen twice in a week");

        CaseEventRecord stored = this.log.all().getFirst();
        assertEquals("STAFF", stored.visibility(), "notes are staff only");
        assertEquals(new CaseEventPayload.NoteAdded(stored.subjectId(), "seen twice in a week"), stored.payload());
    }

    @Test
    void aNoteOf4001CharactersIsRejectedAndOf4000Accepted() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.addNote(ID, new CaseDTO.NoteRequest("x".repeat(4001)), ALICE)));
        assertNotNull(addNote(ALICE, "x".repeat(4000)), "the limit itself is allowed");
    }

    @Test
    void aBlankNoteIsRejected() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.addNote(ID, new CaseDTO.NoteRequest("   "), ALICE)));
    }

    @Test
    void aNoteWithAUuidIsRejectedAndNothingIsStored() {
        givenPunishment(ID);

        CaseDTO result = this.service.addNote(ID, new CaseDTO.NoteRequest("it was " + UUID.randomUUID()), ALICE);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(result));
        assertTrue(this.log.all().isEmpty(), "nothing is stored");
    }

    @Test
    void aNoteWithAnIpAddressIsRejected() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.addNote(ID, new CaseDTO.NoteRequest("from 203.0.113.9"), ALICE)));
    }

    @Test
    void aNoteWithAnIssuedBanIdIsRejectedAndNothingIsStored() {
        givenPunishment(ID);
        String banId = this.banIds.issueFor(ID);

        CaseDTO result = this.service.addNote(ID, new CaseDTO.NoteRequest("player said #" + banId), ALICE);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(result));
        assertTrue(this.log.all().isEmpty(), "nothing is stored");
    }

    @Test
    void aNoteMayNameALegacyShortId() {
        givenPunishment(ID);

        assertNotNull(addNote(ALICE, "same player as #A1B2C3D4"), "a hex short id that is no Ban ID is a normal reference");
    }

    @Test
    void theAuthorCanEditTheirNoteAndTheEarlierVersionStays() {
        givenPunishment(ID);
        String note = addNote(ALICE, "first");

        CaseDTO result = this.service.editNote(ID, UUID.fromString(note), new CaseDTO.NoteRequest("second"), ALICE);

        assertInstanceOf(CaseDTO.NoteResponse.class, result);
        assertEquals(List.of("note.added", "note.edited"), this.log.all().stream().map(CaseEventRecord::type).toList());
        assertEquals(new CaseEventPayload.NoteAdded(note, "first"), this.log.all().getFirst().payload(), "the added row is unchanged");
    }

    @Test
    void anotherModeratorCannotEditTheNote() {
        givenPunishment(ID);
        String note = addNote(ALICE, "first");

        assertEquals(CaseErrorKind.FORBIDDEN, kindOf(this.service.editNote(ID, UUID.fromString(note), new CaseDTO.NoteRequest("mine now"), BOB)));
        assertEquals(1, this.log.all().size(), "no edit event");
    }

    @Test
    void anAdminCanEditSomeoneElsesNote() {
        givenPunishment(ID);
        String note = addNote(ALICE, "first");

        assertInstanceOf(CaseDTO.NoteResponse.class, this.service.editNote(ID, UUID.fromString(note), new CaseDTO.NoteRequest("fixed"), ADMIN));
    }

    @Test
    void aNoteWrittenByAnUnverifiedActorIsEditableByAnAdminOnly() {
        givenPunishment(ID);
        UUID note = UUID.randomUUID();
        this.log.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded(note.toString(), "legacy"), 1, CaseEventOrigin.LIVE,
                new CaseActor(ActorTrust.UNVERIFIED, ALICE.oid(), null, null), note.toString(), null));

        assertEquals(CaseErrorKind.FORBIDDEN, kindOf(this.service.editNote(ID, note, new CaseDTO.NoteRequest("x"), ALICE)),
                "an unverified note has no verified author");
        assertInstanceOf(CaseDTO.NoteResponse.class, this.service.editNote(ID, note, new CaseDTO.NoteRequest("x"), ADMIN));
    }

    @Test
    void editingAnUnknownNoteAnswers404() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.NOT_FOUND, kindOf(this.service.editNote(ID, UUID.randomUUID(), new CaseDTO.NoteRequest("x"), ALICE)));
    }

    @Test
    void redactionRemovesEveryVersionKeepsTheActorAndAppendsNoteRedacted() {
        givenPunishment(ID);
        String note = addNote(ALICE, "john lives at home");
        this.service.editNote(ID, UUID.fromString(note), new CaseDTO.NoteRequest("john lives at a street"), ALICE);

        CaseDTO result = this.service.redactNote(ID, UUID.fromString(note), new CaseDTO.RedactRequest(NoteRedactionReason.PERSONAL_DATA), ADMIN);

        assertEquals(2, assertInstanceOf(CaseDTO.RedactResponse.class, result).redactedVersions());
        List<CaseEventRecord> rows = this.log.all();
        assertEquals(List.of("note.added", "note.edited", "note.redacted"), rows.stream().map(CaseEventRecord::type).toList());
        assertTrue(rows.subList(0, 2).stream().allMatch(row -> row.redactedAt() != null && row.body().isEmpty()), "no text remains");
        assertEquals(ALICE.oid(), rows.getFirst().actorOid(), "the original actor stays");
        assertEquals(new CaseEventPayload.NoteRedacted(note, "PERSONAL_DATA"), rows.get(2).payload(), "the redaction event holds no text");
    }

    @Test
    void aRedactedNoteCannotBeEditedAgain() {
        givenPunishment(ID);
        String note = addNote(ALICE, "text");
        this.service.redactNote(ID, UUID.fromString(note), new CaseDTO.RedactRequest(NoteRedactionReason.OTHER), ADMIN);

        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.editNote(ID, UUID.fromString(note), new CaseDTO.NoteRequest("back"), ALICE)));
    }

    @Test
    void redactingTwiceAppendsOneRedactionEvent() {
        givenPunishment(ID);
        String note = addNote(ALICE, "text");
        this.service.redactNote(ID, UUID.fromString(note), new CaseDTO.RedactRequest(NoteRedactionReason.OTHER), ADMIN);

        this.service.redactNote(ID, UUID.fromString(note), new CaseDTO.RedactRequest(NoteRedactionReason.OTHER), ADMIN);

        assertEquals(1, this.log.all().stream().filter(row -> row.type().equals("note.redacted")).count(), "one redaction event");
    }

    @Test
    void redactingWithoutAReasonIsRejected() {
        givenPunishment(ID);
        String note = addNote(ALICE, "text");

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.redactNote(ID, UUID.fromString(note), new CaseDTO.RedactRequest(null), ADMIN)));
    }

    @Test
    void theNoteAddedEventCarriesTheNoteIdAndNeverTheText() {
        givenPunishment(ID);

        String note = addNote(ALICE, "private words");

        var published = this.events.eventsOfType("case.note_added").getFirst().payload();
        assertEquals(note, published.get("noteId"));
        assertEquals(ID, published.get("punishmentIdentifier"));
        assertEquals(OWNER, published.get("owner"), "the hashed owner identifies the player");
        assertFalse(published.toString().contains("private words"), "the broker never receives note text");
    }

    @Test
    void aStatusChangeEventCarriesFromAndTo() {
        givenPunishment(ID);

        this.service.update(ID, patch(0, CaseStatus.CLOSED, null, null, null), ALICE);

        var published = this.events.eventsOfType("case.status_changed").getFirst().payload();
        assertEquals("OPEN", published.get("from"));
        assertEquals("CLOSED", published.get("to"));
        assertEquals("VERIFIED", published.get("actorTrust"));
    }

    @Test
    void aClosedCaseWithAnActivePunishmentIsAllowed() {
        givenPunishment(ID);

        CaseDTO.Response closed = assertInstanceOf(CaseDTO.Response.class, this.service.update(ID, patch(0, CaseStatus.CLOSED, null, null, null), ALICE));

        assertEquals(CaseStatus.CLOSED, closed.status(), "status is a workflow, not enforcement");
        assertEquals("ACTIVE", closed.punishmentState());
    }

    @Test
    void readingACaseWithoutARowAnswersTheDefaultsAndWritesNothing() {
        givenPunishment(ID);

        CaseDTO.Response response = assertInstanceOf(CaseDTO.Response.class, this.service.get(ID));

        assertEquals(CaseStatus.OPEN, response.status());
        assertEquals(CasePriority.NORMAL, response.priority());
        assertEquals(0, response.version());
        assertEquals(OWNER.substring(0, 12), response.ownerPrefix(), "only a hash prefix is shown");
        assertFalse(this.store.hasCase(ID), "a read never creates a case");
    }

    @Test
    void anAmbiguousShortIdAnswers409() {
        // Identifiers that share their first four bytes share a short id.
        givenPunishment("AAAAAAAAAAAAAAAAAAAAAA");
        givenPunishment("AAAAAAAAAAAAAAAAAAAAAQ");

        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.get("#00000000")));
    }

    @Test
    void anUnknownPunishmentAnswers404() {
        assertEquals(CaseErrorKind.NOT_FOUND, kindOf(this.service.get("does-not-exist")));
    }

    @Test
    void aMalformedCursorAnswers400() {
        givenPunishment(ID);

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.timeline(ID, "garbage", null, null, null, null, null, null)));
    }

    private CaseDTO.TimelineItem timelineItem(String type) {
        CaseDTO.TimelinePage page = assertInstanceOf(CaseDTO.TimelinePage.class, this.service.timeline(ID, null, null, null, null, null, null, null));
        return page.items().stream().filter(item -> item.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void aMessageInTheTimelineCarriesItsSourceAndTheParsedBlocks() {
        givenPunishment(ID);
        this.log.append(new CaseEventDraft(ID, new CaseEventPayload.MessageAdded("m-1", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL,
                "**hi** [x](https://example.org/)"), 1L, CaseEventOrigin.LIVE, CaseActor.player(), "m-1", "message.added:m-1", CaseEventDraft.PLAYER));

        CaseDTO.TimelineItem item = timelineItem("message.added");

        assertEquals("**hi** [x](https://example.org/)", item.payload().get("text"), "the stored source is unchanged");
        assertEquals(1, item.blocks().size(), "one paragraph");
        assertEquals("paragraph", item.blocks().getFirst().get("type"));
    }

    @Test
    void aRedactedMessageHasNoBlocks() {
        givenPunishment(ID);
        this.log.append(new CaseEventDraft(ID, new CaseEventPayload.MessageAdded("m-1", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL,
                "secret"), 1L, CaseEventOrigin.LIVE, CaseActor.player(), "m-1", "message.added:m-1", CaseEventDraft.PLAYER));
        this.log.redact(ID, "m-1", 2L, UUID.randomUUID());

        CaseDTO.TimelineItem item = timelineItem("message.added");

        assertNull(item.blocks(), "nothing is left to parse after a redaction");
    }

    @Test
    void aNoteIsPlainTextAndHasNoBlocks() {
        givenPunishment(ID);
        addNote(ALICE, "**not** markdown");

        assertNull(timelineItem("note.added").blocks(), "notes are plain staff text");
    }
}
