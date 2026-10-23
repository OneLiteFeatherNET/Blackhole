package net.onelitefeather.blackhole.backend.casetimeline.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRepository;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEvents;
import net.onelitefeather.blackhole.backend.casetimeline.CaseMeta;
import net.onelitefeather.blackhole.backend.casetimeline.CasePriority;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTags;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The case API: reads the case and its timeline, changes its metadata and keeps staff notes. Expected
 * failures come back as {@link CaseDTO.Error}, never as exceptions.
 *
 * <p>A change of the case row and its event is one SQL statement ({@link CaseChanges}); a note is its own
 * event. {@code @Transactional} is unusable in this codebase and not needed. {@code case.*} domain events
 * go out after the write, in a try/catch, and never carry note text.</p>
 */
@Singleton
public class CaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseService.class);

    /** The longest note, in characters. */
    public static final int MAX_NOTE = 4000;
    public static final int DEFAULT_TIMELINE_SIZE = 50;
    public static final int MAX_TIMELINE_SIZE = 200;
    static final int DEFAULT_LIST_SIZE = 20;
    static final int MAX_LIST_SIZE = 100;
    static final int MAX_RELATED = 20;
    private static final int OWNER_PREFIX = 12;
    private static final Pattern TYPE_FILTER = Pattern.compile("[a-z_]+(\\.[a-z_]+)*");
    private static final Pattern CURSOR = Pattern.compile("(\\d{1,18}):(\\d{1,18})");
    private static final List<String> STATES = List.of("ACTIVE", "EXPIRED", "LIFTED");

    private final PunishmentRepository punishments;
    private final StaffActorRepository staff;
    private final CaseReader reader;
    private final CaseChanges changes;
    private final CaseEventRepository events;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public CaseService(
            PunishmentRepository punishments, StaffActorRepository staff, CaseReader reader, CaseChanges changes,
            CaseEventRepository events, DomainEventPublisher publisher, Clock clock
    ) {
        this.punishments = punishments;
        this.staff = staff;
        this.reader = reader;
        this.changes = changes;
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
    }

    private record Found(PunishmentEntity punishment, CaseDTO.Error error) {
    }

    /**
     * The case of a punishment given by identifier or short id, with related punishments of the same
     * owner. A punishment without a case row reads as the default case; the read writes nothing.
     *
     * @return the {@link CaseDTO.Response}, or an error: 404 unknown, 409 ambiguous short id
     */
    public CaseDTO get(String reference) {
        Found found = find(reference);
        return found.error() != null ? found.error() : view(found.punishment());
    }

    /**
     * One page of the timeline.
     *
     * @param cursor the {@code nextCursor} of the previous page
     * @param types  exact types or the groups {@code note}, {@code appeal}, {@code evidence}, {@code case}, {@code punishment}
     * @param order  {@code asc} (default, oldest first) or {@code desc}
     */
    public CaseDTO timeline(
            String reference, @Nullable String cursor, @Nullable Integer size, @Nullable List<String> types, @Nullable String actorTrust,
            @Nullable Long from, @Nullable Long to, @Nullable String order
    ) {
        Found found = find(reference);
        if (found.error() != null) {
            return found.error();
        }
        List<String> typeFilter = types == null ? List.of() : types;
        if (typeFilter.stream().anyMatch(type -> !TYPE_FILTER.matcher(type).matches())) {
            return error(CaseErrorKind.VALIDATION, "a type filter is a dotted lower case type or group such as note or appeal.filed");
        }
        ActorTrust trust = null;
        if (actorTrust != null) {
            try {
                trust = ActorTrust.valueOf(actorTrust);
            } catch (IllegalArgumentException e) {
                return error(CaseErrorKind.VALIDATION, "actorTrust is VERIFIED, UNVERIFIED or SYSTEM");
            }
        }
        if (order != null && !order.equals("asc") && !order.equals("desc")) {
            return error(CaseErrorKind.VALIDATION, "order is asc or desc");
        }
        Long afterOccurredAt = null;
        Long afterSeq = null;
        if (cursor != null) {
            var matcher = CURSOR.matcher(cursor);
            if (!matcher.matches()) {
                return error(CaseErrorKind.VALIDATION, "the cursor is not one this API returned");
            }
            afterOccurredAt = Long.parseLong(matcher.group(1));
            afterSeq = Long.parseLong(matcher.group(2));
        }
        int limit = size == null ? DEFAULT_TIMELINE_SIZE : Math.max(1, Math.min(size, MAX_TIMELINE_SIZE));
        List<CaseReader.Entry> entries = this.reader.timeline(new CaseReader.TimelineQuery(
                found.punishment().getIdentifier(), afterOccurredAt, afterSeq, typeFilter, trust, from, to, !"desc".equals(order), limit + 1));
        List<CaseReader.Entry> shown = entries.size() > limit ? entries.subList(0, limit) : entries;
        String next = null;
        if (entries.size() > limit) {
            CaseEventRecord last = shown.getLast().event();
            next = last.occurredAt() + ":" + last.seq();
        }
        return new CaseDTO.TimelinePage(shown.stream().map(CaseService::toItem).toList(), next);
    }

    /** The case list, newest activity first. */
    public CaseDTO list(
            @Nullable CaseStatus status, @Nullable UUID assignee, @Nullable String tag, @Nullable CasePriority priority,
            @Nullable String section, @Nullable String punishmentState, @Nullable Integer page, @Nullable Integer size
    ) {
        if (tag != null && !CaseTags.VALID.matcher(tag).matches()) {
            return error(CaseErrorKind.VALIDATION, "a tag is a slug of lower case letters, digits and dashes");
        }
        if (punishmentState != null && !STATES.contains(punishmentState)) {
            return error(CaseErrorKind.VALIDATION, "punishmentState is ACTIVE, EXPIRED or LIFTED");
        }
        int pageNumber = page == null ? 0 : Math.max(0, page);
        int pageSize = size == null ? DEFAULT_LIST_SIZE : Math.max(1, Math.min(size, MAX_LIST_SIZE));
        CaseReader.ListPage result = this.reader.list(new CaseReader.ListQuery(
                status == null ? null : status.name(), assignee, tag, priority == null ? null : priority.name(), section, punishmentState,
                pageNumber, pageSize), this.clock.millis());
        List<CaseDTO.ListItem> items = result.items().stream().map(row -> new CaseDTO.ListItem(
                row.identifier(), row.shortId(), row.section(), row.state(), CaseStatus.valueOf(row.meta().status()), row.meta().assignee(),
                row.assigneeName(), CasePriority.valueOf(row.meta().priority()), row.meta().tags(), row.createdAt(), row.lastActivityAt())).toList();
        return new CaseDTO.ListPage(items, pageNumber, pageSize, result.total(), (int) Math.ceil(result.total() / (double) pageSize));
    }

    /**
     * Changes status, assignee, priority and tags. Answers the changed case.
     *
     * @return the {@link CaseDTO.Response}, or an error: 400 invalid tags or a missing version, 404, 409 stale version or
     * ambiguous short id, 422 an assignee who is no known human staff member
     */
    public CaseDTO update(String reference, CaseDTO.UpdateRequest request, Actor actor) {
        Found found = find(reference);
        if (found.error() != null) {
            return found.error();
        }
        if (request.expectedVersion() == null) {
            return error(CaseErrorKind.VALIDATION, "expectedVersion is required");
        }
        List<String> tags = null;
        if (request.tags() != null) {
            tags = normalizeTags(request.tags());
            if (tags == null) {
                return error(CaseErrorKind.VALIDATION, "tags are at most " + CaseTags.MAX + " slugs of [a-z0-9-], 1 to 32 characters each");
            }
        }
        if (request.assigneeOid() != null && !isKnownHuman(request.assigneeOid())) {
            return error(CaseErrorKind.UNPROCESSABLE, "the assignee is not a known staff member");
        }
        PunishmentEntity punishment = found.punishment();
        Optional<CaseReader.CaseRow> current = this.reader.find(punishment.getIdentifier());
        CaseMeta before = current.map(CaseReader.CaseRow::meta).orElse(CaseMeta.defaults());
        long version = current.map(CaseReader.CaseRow::version).orElse(0L);
        if (request.expectedVersion() != version) {
            return stale();
        }
        CaseMeta after = new CaseMeta(
                request.status() != null ? request.status().name() : before.status(),
                request.clearAssignee() ? null : request.assigneeOid() != null ? request.assigneeOid() : before.assignee(),
                request.priority() != null ? request.priority().name() : before.priority(),
                tags != null ? tags : before.tags());
        List<CaseEventPayload> payloads = after.diffFrom(before);
        if (payloads.isEmpty()) {
            return view(punishment);
        }
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        if (current.isEmpty()) {
            this.changes.ensureCase(punishment.getIdentifier(), now);
        }
        List<CaseChanges.Appended> appended = this.changes.update(punishment.getIdentifier(), version, now, by, before, after);
        if (appended.isEmpty()) {
            return stale();
        }
        for (CaseChanges.Appended event : appended) {
            payloads.stream().filter(payload -> payload.type().equals(event.type())).findFirst()
                    .ifPresent(payload -> publish(punishment, event, by, now, payload.toMap()));
        }
        return view(punishment);
    }

    /** Adds a staff note. Any visibility in the request was never read: a note is always {@code STAFF}. */
    public CaseDTO addNote(String reference, CaseDTO.NoteRequest request, Actor actor) {
        Found found = find(reference);
        if (found.error() != null) {
            return found.error();
        }
        CaseDTO.Error invalid = checkText(request.text());
        if (invalid != null) {
            return invalid;
        }
        PunishmentEntity punishment = found.punishment();
        String noteId = UUID.randomUUID().toString();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        this.events.append(new CaseEventDraft(punishment.getIdentifier(), new CaseEventPayload.NoteAdded(noteId, request.text()), now,
                CaseEventOrigin.LIVE, by, noteId, null));
        CaseEventRecord stored = lastOfType(punishment.getIdentifier(), noteId, CaseEventPayload.NOTE_ADDED);
        publish(punishment, stored, by, now, Map.of("noteId", noteId));
        return new CaseDTO.NoteResponse(noteId, stored.seq());
    }

    /**
     * Edits a note by appending {@code note.edited}; the earlier versions stay.
     *
     * @return the {@link CaseDTO.NoteResponse}, or an error: 400 invalid text, 403 not the author (an admin may), 404, 409 redacted
     */
    public CaseDTO editNote(String reference, UUID noteId, CaseDTO.NoteRequest request, Actor actor) {
        Found found = find(reference);
        if (found.error() != null) {
            return found.error();
        }
        CaseDTO.Error invalid = checkText(request.text());
        if (invalid != null) {
            return invalid;
        }
        PunishmentEntity punishment = found.punishment();
        List<CaseEventRecord> versions = noteVersions(punishment.getIdentifier(), noteId.toString());
        if (versions.isEmpty()) {
            return error(CaseErrorKind.NOT_FOUND, "there is no such note");
        }
        if (versions.stream().anyMatch(version -> version.redactedAt() != null)) {
            return error(CaseErrorKind.CONFLICT, "the note was redacted");
        }
        CaseEventRecord added = versions.getFirst();
        boolean author = added.actorTrust() == ActorTrust.VERIFIED && actor.oid().equals(added.actorOid());
        if (!author && !actor.admin()) {
            return error(CaseErrorKind.FORBIDDEN, "only the author of a note or an admin may edit it");
        }
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        CaseChanges.Appended edited = this.changes.editNote(punishment.getIdentifier(), noteId.toString(), request.text(), now, by);
        if (edited == null) {
            return error(CaseErrorKind.CONFLICT, "the note was redacted");
        }
        publish(punishment, edited, by, now, Map.of("noteId", noteId.toString()));
        return new CaseDTO.NoteResponse(noteId.toString(), edited.seq());
    }

    /**
     * Redacts every version of a note and appends {@code note.redacted} (once per note, however often it is asked).
     *
     * @return the {@link CaseDTO.RedactResponse}, or an error: 404 unknown note
     */
    public CaseDTO redactNote(String reference, UUID noteId, CaseDTO.RedactRequest request, Actor actor) {
        Found found = find(reference);
        if (found.error() != null) {
            return found.error();
        }
        if (request.reason() == null) {
            return error(CaseErrorKind.VALIDATION, "a reason is required");
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        if (noteVersions(id, noteId.toString()).isEmpty()) {
            return error(CaseErrorKind.NOT_FOUND, "there is no such note");
        }
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        int redacted = this.events.redact(id, noteId.toString(), now, actor.oid());
        boolean appended = this.events.append(new CaseEventDraft(id, new CaseEventPayload.NoteRedacted(noteId.toString(), request.reason().name()),
                now, CaseEventOrigin.LIVE, by, noteId.toString(), "note.redacted:" + noteId));
        if (appended) {
            CaseEventRecord stored = lastOfType(id, noteId.toString(), CaseEventPayload.NOTE_REDACTED);
            publish(punishment, stored, by, now, Map.of("noteId", noteId.toString(), "reason", request.reason().name()));
        }
        return new CaseDTO.RedactResponse(noteId.toString(), redacted);
    }

    private CaseDTO.Response view(PunishmentEntity punishment) {
        long now = this.clock.millis();
        Optional<CaseReader.CaseRow> row = this.reader.find(punishment.getIdentifier());
        CaseMeta meta = row.map(CaseReader.CaseRow::meta).orElse(CaseMeta.defaults());
        List<CaseDTO.Related> related = this.reader.related(punishment.getOwner(), punishment.getIdentifier(), now, MAX_RELATED).stream()
                .map(other -> new CaseDTO.Related(other.identifier(), other.shortId(), other.section(), other.state(), other.caseStatus(), other.createdAt()))
                .toList();
        String owner = punishment.getOwner();
        return new CaseDTO.Response(punishment.getIdentifier(), PunishmentShortId.display(punishment.getShortId()),
                owner.substring(0, Math.min(OWNER_PREFIX, owner.length())), punishment.getSectionKey(), state(punishment, now),
                punishment.isPermanent(), punishment.getCreatedAt() == null ? 0 : punishment.getCreatedAt(),
                CaseStatus.valueOf(meta.status()), meta.assignee(), row.map(CaseReader.CaseRow::assigneeName).orElse(null),
                CasePriority.valueOf(meta.priority()), meta.tags(), row.map(CaseReader.CaseRow::version).orElse(0L), related);
    }

    /** {@code LIFTED} (revoked or lifted by an appeal), {@code EXPIRED} or {@code ACTIVE}. */
    static String state(PunishmentEntity punishment, long now) {
        SourceRows.PunishmentRow row = SourceRows.PunishmentRow.of(punishment);
        if (row.lifted()) {
            return "LIFTED";
        }
        return !row.permanent() && row.expiresAt() != null && row.expiresAt() <= now ? "EXPIRED" : "ACTIVE";
    }

    private static CaseDTO.TimelineItem toItem(CaseReader.Entry entry) {
        CaseEventRecord event = entry.event();
        return new CaseDTO.TimelineItem(event.seq(), event.identifier(), event.type(), event.payloadVersion(), event.occurredAt(),
                event.origin(), event.visibility(), new CaseDTO.ActorInfo(event.actorOid(), entry.actorName(), event.actorTrust()),
                event.subjectId(), event.body(), event.redactedAt());
    }

    private List<CaseEventRecord> noteVersions(String punishmentIdentifier, String noteId) {
        return this.reader.subject(punishmentIdentifier, noteId).stream()
                .filter(event -> event.type().equals(CaseEventPayload.NOTE_ADDED) || event.type().equals(CaseEventPayload.NOTE_EDITED)).toList();
    }

    private CaseEventRecord lastOfType(String punishmentIdentifier, String subjectId, String type) {
        return this.reader.subject(punishmentIdentifier, subjectId).stream().filter(event -> event.type().equals(type)).reduce((a, b) -> b)
                .orElseThrow(() -> new IllegalStateException("the " + type + " event of " + subjectId + " was not stored"));
    }

    private void publish(PunishmentEntity punishment, CaseEventRecord event, CaseActor by, long now, Map<String, Object> change) {
        publish(punishment, new CaseChanges.Appended(event.identifier(), event.seq(), event.type()), by, now, change);
    }

    /** Publishes after the write; a broker problem never fails the request that already changed the case. */
    private void publish(PunishmentEntity punishment, CaseChanges.Appended event, CaseActor by, long now, Map<String, Object> change) {
        try {
            this.publisher.publish(CaseEvents.routingKey(event.type()),
                    CaseEvents.payload(event.identifier(), punishment.getIdentifier(), punishment.getOwner(), event.seq(), by, now, change));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {} for punishment {}", event.type(), punishment.getIdentifier(), e);
        }
    }

    private boolean isKnownHuman(UUID oid) {
        return this.staff.findById(oid).filter(actor -> actor.getKind() == ActorKind.HUMAN).isPresent();
    }

    /** The sorted unique tags, or {@code null} when a tag is no slug or there are too many. */
    static @Nullable List<String> normalizeTags(List<String> tags) {
        TreeSet<String> unique = new TreeSet<>();
        for (String tag : tags) {
            if (tag == null || !CaseTags.VALID.matcher(tag).matches()) {
                return null;
            }
            unique.add(tag);
        }
        return unique.size() > CaseTags.MAX ? null : new ArrayList<>(unique);
    }

    private static @Nullable CaseDTO.Error checkText(@Nullable String text) {
        if (text == null || text.isBlank() || text.length() > MAX_NOTE) {
            return error(CaseErrorKind.VALIDATION, "a note is 1 to " + MAX_NOTE + " characters");
        }
        if (PiiGuard.containsUuid(text) || PiiGuard.containsIp(text)) {
            return error(CaseErrorKind.VALIDATION, "a note must not contain a UUID or an IP address; name a player by the short id");
        }
        return null;
    }

    private static CaseDTO.Error stale() {
        return error(CaseErrorKind.CONFLICT, "the case was changed by someone else; reload it and try again");
    }

    private static CaseDTO.Error error(CaseErrorKind kind, String message) {
        return new CaseDTO.Error(message, kind);
    }

    /** Finds a punishment by its full identifier, or by its short id when that matches exactly one. */
    private Found find(String reference) {
        if (reference == null || reference.isBlank()) {
            return new Found(null, error(CaseErrorKind.VALIDATION, "a punishment is required"));
        }
        Optional<PunishmentEntity> byId = this.punishments.findById(reference.strip());
        if (byId.isPresent()) {
            return new Found(byId.get(), null);
        }
        Optional<String> key = PunishmentShortId.parse(reference);
        if (key.isEmpty()) {
            return new Found(null, error(CaseErrorKind.NOT_FOUND, "there is no such punishment"));
        }
        List<PunishmentEntity> matches = this.punishments.findByShortId(key.get());
        if (matches.isEmpty()) {
            return new Found(null, error(CaseErrorKind.NOT_FOUND, "there is no punishment with the short id " + PunishmentShortId.display(key.get())));
        }
        if (matches.size() > 1) {
            String candidates = String.join(", ", matches.stream().map(PunishmentEntity::getIdentifier).toList());
            return new Found(null, error(CaseErrorKind.CONFLICT, "the short id " + PunishmentShortId.display(key.get())
                    + " matches several punishments, use the full identifier: " + candidates));
        }
        return new Found(matches.getFirst(), null);
    }
}
