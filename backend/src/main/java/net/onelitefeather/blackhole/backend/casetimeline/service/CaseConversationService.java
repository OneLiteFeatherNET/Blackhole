package net.onelitefeather.blackhole.backend.casetimeline.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdCollisionException;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDrafts;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPublisher;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRepository;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;
import net.onelitefeather.blackhole.backend.casetimeline.MessageRules;
import net.onelitefeather.blackhole.backend.casetimeline.MessageThread;
import net.onelitefeather.blackhole.backend.casetimeline.TranscriptRenderer;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.security.Actor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The conversation of a case: messages between the player and the team, the Ban ID leak rule, the
 * erasure of messages and the transcript export. Expected failures come back as
 * {@link CaseConversationDTO.Error}, never as exceptions; an unexpected failure to store a message
 * propagates so the request fails and nothing is lost silently.
 *
 * <p>Every change is one SQL statement ({@link CaseChanges}) or one event append. {@code @Transactional}
 * is unusable in this codebase and not needed. The {@code case.*} domain events go out after the write,
 * in a try/catch, and never carry message text.</p>
 */
@Singleton
public class CaseConversationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseConversationService.class);
    /** The longest {@code Idempotency-Key}. */
    static final int MAX_IDEMPOTENCY_KEY = 80;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_IDEMPOTENCY_KEY + "}");
    private static final int GENERATION_ATTEMPTS = 5;

    private final CaseReference references;
    private final CaseReader reader;
    private final CaseChanges changes;
    private final CaseEventRepository events;
    private final CaseEventPublisher publisher;
    private final Clock clock;
    private final BanIdService banIds;

    public CaseConversationService(
            PunishmentRepository punishments, CaseReader reader, CaseChanges changes, CaseEventRepository events,
            DomainEventPublisher publisher, Clock clock, BanIdService banIds
    ) {
        this.references = new CaseReference(punishments);
        this.reader = reader;
        this.changes = changes;
        this.events = events;
        this.publisher = new CaseEventPublisher(publisher);
        this.clock = clock;
        this.banIds = banIds;
    }

    /**
     * A staff reply from the panel. It is always visible to the player; the request cannot choose.
     *
     * @param idempotencyKey makes a repeated post a no-op that answers with the first message
     * @return the {@link CaseConversationDTO.MessageResponse}, or an error: 400 invalid text or key, 404, 409 ambiguous short id
     */
    public CaseConversationDTO postMessage(String reference, CaseConversationDTO.MessageRequest request, Actor actor, @Nullable String idempotencyKey) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        CaseConversationDTO.Error invalid = checkText(request.text());
        if (invalid == null) {
            invalid = checkKey(idempotencyKey);
        }
        if (invalid != null) {
            return invalid;
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        String messageId = idempotencyKey == null ? UUID.randomUUID().toString()
                : CaseDrafts.idempotentMessageId(id, actor.oid().toString(), idempotencyKey);
        CaseActor by = CaseActor.of(actor.attribution());
        long now = this.clock.millis();
        CaseEventDraft draft = CaseDrafts.staffMessage(id, messageId, null, request.text(), MessageChannel.PANEL, by, CaseEventOrigin.LIVE, now);
        return store(punishment, draft, false, by, now);
    }

    /**
     * A message of the player, from the portal: trust {@code PLAYER}, visible to the player, and a case that
     * waits for the player moves to {@code IN_REVIEW}. A case whose Ban ID is marked leaked is frozen:
     * the player side can no longer post (409).
     *
     * @param punishmentIdentifier the full identifier, as the public API derives it from the session
     * @param appealId             the appeal the message belongs to, if any
     */
    public CaseConversationDTO postPlayerMessage(
            String punishmentIdentifier, @Nullable UUID appealId, String text, @Nullable String idempotencyKey
    ) {
        CaseReference.Found found = this.references.resolve(punishmentIdentifier);
        if (found.failed()) {
            return error(found);
        }
        CaseConversationDTO.Error invalid = checkText(text);
        if (invalid == null) {
            invalid = checkKey(idempotencyKey);
        }
        if (invalid != null) {
            return invalid;
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        if (this.reader.find(id).filter(CaseReader.CaseRow::leaked).isPresent()) {
            return error(CaseErrorKind.CONFLICT, "the Ban ID of this ticket is marked leaked; the ticket is frozen");
        }
        String messageId = idempotencyKey == null ? UUID.randomUUID().toString() : CaseDrafts.idempotentMessageId(id, "player", idempotencyKey);
        long now = this.clock.millis();
        CaseEventDraft draft = CaseDrafts.playerMessage(id, messageId, appealId == null ? null : appealId.toString(), text, now);
        return store(punishment, draft, true, CaseActor.player(), now);
    }

    /**
     * Corrects a staff message by appending {@code message.edited}; earlier versions stay. Only the verified
     * author or an admin may, only staff messages, and not once the message is deleted or redacted.
     *
     * @return the {@link CaseConversationDTO.MessageResponse}, or an error: 400, 403, 404, 409
     */
    public CaseConversationDTO editMessage(String reference, UUID messageId, CaseConversationDTO.MessageRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        CaseConversationDTO.Error invalid = checkText(request.text());
        if (invalid != null) {
            return invalid;
        }
        PunishmentEntity punishment = found.punishment();
        Checked checked = checkEditable(punishment, messageId, actor);
        if (checked.error() != null) {
            return checked.error();
        }
        MessageThread.Message message = checked.message();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        CaseChanges.Appended edited = this.changes.editMessage(CaseDrafts.messageEdited(punishment.getIdentifier(), messageId.toString(),
                message.edits() + 1, request.text(), message.visibility(), by, now));
        if (edited == null) {
            return error(CaseErrorKind.CONFLICT, "the message was changed, deleted or redacted in the meantime");
        }
        this.publisher.publish(punishment, edited, by, now, messageChange(message, message.visibility()));
        return new CaseConversationDTO.MessageResponse(messageId.toString(), edited.seq(), false);
    }

    /**
     * Deletes a staff message by appending {@code message.deleted}: the player sees it removed, staff see the
     * last text marked deleted, the export keeps it. Same rules as {@link #editMessage}.
     */
    public CaseConversationDTO deleteMessage(String reference, UUID messageId, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        PunishmentEntity punishment = found.punishment();
        Checked checked = checkEditable(punishment, messageId, actor);
        if (checked.error() != null) {
            return checked.error();
        }
        MessageThread.Message message = checked.message();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        String id = punishment.getIdentifier();
        boolean appended = this.events.append(CaseDrafts.messageDeleted(id, messageId.toString(), MessageAuthorKind.STAFF, message.visibility(), by, now));
        if (!appended) {
            return error(CaseErrorKind.CONFLICT, "the message is already deleted");
        }
        CaseEventRecord stored = lastOfType(id, messageId.toString(), CaseEventPayload.MESSAGE_DELETED);
        this.publisher.publish(punishment, new CaseChanges.Appended(stored.identifier(), stored.seq(), stored.type()), by, now,
                messageChange(message, message.visibility()));
        return new CaseConversationDTO.MessageResponse(messageId.toString(), stored.seq(), false);
    }

    /**
     * Redacts every version of one message (text and attachment metadata go; type, time, author kind and
     * visibility stay) and appends {@code message.redacted}, once however often it is asked.
     *
     * @return the {@link CaseConversationDTO.RedactResponse}, or an error: 400 no reason, 404 unknown message
     */
    public CaseConversationDTO redactMessage(String reference, UUID messageId, CaseConversationDTO.RedactRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        if (request.reason() == null) {
            return error(CaseErrorKind.VALIDATION, "a reason is required");
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        List<MessageThread.Message> thread = MessageThread.of(this.reader.subject(id, messageId.toString()));
        if (thread.isEmpty()) {
            return error(CaseErrorKind.NOT_FOUND, "there is no such message");
        }
        MessageThread.Message message = thread.getFirst();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        int redacted = this.events.redact(id, messageId.toString(), now, actor.oid());
        boolean appended = this.events.append(CaseDrafts.messageRedacted(id, messageId.toString(), request.reason().name(), message.visibility(), by, now));
        if (appended) {
            CaseEventRecord stored = lastOfType(id, messageId.toString(), CaseEventPayload.MESSAGE_REDACTED);
            Map<String, Object> change = messageChange(message, message.visibility());
            change.put("reason", request.reason().name());
            this.publisher.publish(punishment, new CaseChanges.Appended(stored.identifier(), stored.seq(), stored.type()), by, now, change);
        }
        return new CaseConversationDTO.RedactResponse(messageId.toString(), redacted);
    }

    /**
     * Redacts every message the player wrote in this case in one statement; staff messages stay.
     *
     * @return the {@link CaseConversationDTO.EraseResponse}, or an error: 400 no reason, 404
     */
    public CaseConversationDTO erasePlayerMessages(String reference, CaseConversationDTO.RedactRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        if (request.reason() == null) {
            return error(CaseErrorKind.VALIDATION, "a reason is required");
        }
        PunishmentEntity punishment = found.punishment();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        List<CaseChanges.Erased> erased = this.changes.erasePlayerMessages(punishment.getIdentifier(), request.reason().name(), now, actor.oid(), by);
        for (CaseChanges.Erased message : erased) {
            Map<String, Object> change = new HashMap<>();
            change.put("messageId", message.messageId());
            change.put("authorKind", MessageAuthorKind.PLAYER.name());
            change.put("visibility", message.visibility());
            change.put("reason", request.reason().name());
            this.publisher.publish(punishment, message.event(), by, now, change);
        }
        return new CaseConversationDTO.EraseResponse(erased.size());
    }

    /**
     * Marks the Ban ID of the current generation leaked, deleting nothing else of the case. Marking it
     * again appends nothing.
     *
     * @return the {@link CaseConversationDTO.LeakResponse}, or an error: 400 no reason, 404, 409
     */
    public CaseConversationDTO markBanIdLeaked(String reference, CaseConversationDTO.LeakRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        if (request.reason() == null) {
            return error(CaseErrorKind.VALIDATION, "a reason is required");
        }
        PunishmentEntity punishment = found.punishment();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        this.changes.ensureCase(punishment.getIdentifier(), now);
        CaseChanges.LeakMarked marked = this.changes.markBanIdLeaked(punishment.getIdentifier(), request.reason(), BanIdLeakSource.STAFF, now, by);
        if (marked == null) {
            return new CaseConversationDTO.LeakResponse(false);
        }
        Map<String, Object> change = new HashMap<>();
        change.put("reason", request.reason().name());
        change.put("source", BanIdLeakSource.STAFF.name());
        change.put("afterFiling", marked.afterFiling());
        this.publisher.publish(punishment, marked.event(), by, now, change);
        return new CaseConversationDTO.LeakResponse(true);
    }

    /**
     * Issues a new Ban ID, clears the leak state and records the generations. The new Ban ID is not
     * returned; staff reveal it with the reveal route.
     *
     * @return the {@link CaseConversationDTO.ReissueResponse}, or an error: 400 no reason, 404, 409 the punishment has no Ban ID yet,
     * 503 no free Ban ID could be drawn
     */
    public CaseConversationDTO reissueBanId(String reference, CaseConversationDTO.ReissueRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        if (request.reason() == null) {
            return error(CaseErrorKind.VALIDATION, "a reason is required");
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        this.changes.ensureCase(id, now);
        for (int attempt = 0; attempt < GENERATION_ATTEMPTS; attempt++) {
            Optional<BanIdService.Material> material = this.banIds.draw(id);
            if (material.isEmpty()) {
                break;
            }
            try {
                CaseChanges.Reissued reissued = this.changes.reissueBanId(id, material.get().hash(), material.get().encrypted(), request.reason(), now, by);
                if (reissued == null) {
                    return error(CaseErrorKind.CONFLICT, "this punishment has no Ban ID to replace yet");
                }
                Map<String, Object> change = new HashMap<>();
                change.put("reason", request.reason().name());
                change.put("fromGeneration", reissued.fromGeneration());
                change.put("toGeneration", reissued.toGeneration());
                this.publisher.publish(punishment, reissued.event(), by, now, change);
                return new CaseConversationDTO.ReissueResponse(reissued.toGeneration());
            } catch (BanIdCollisionException e) {
                LOGGER.info("A new Ban ID candidate for punishment {} was taken; drawing another", id);
            }
        }
        return error(CaseErrorKind.UNAVAILABLE, "no free Ban ID could be drawn; try again");
    }

    /**
     * The conversation and lifecycle of the case as a file. The export is audited first: when
     * {@code transcript.exported} cannot be stored, nothing is handed out.
     *
     * @param format {@code json} (default) or {@code text}
     * @return the {@link CaseConversationDTO.Transcript}, or an error: 400 unknown format, 404, 409
     */
    public CaseConversationDTO exportTranscript(String reference, @Nullable String format, boolean includeNotes, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found);
        }
        String chosen = format == null ? "json" : format.toLowerCase(Locale.ROOT);
        if (!chosen.equals("json") && !chosen.equals("text")) {
            return error(CaseErrorKind.VALIDATION, "format is json or text");
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        List<CaseReader.Entry> entries = this.reader.timeline(
                new CaseReader.TimelineQuery(id, null, null, List.of(), null, null, null, true, Integer.MAX_VALUE));
        String shortId = PunishmentShortId.display(punishment.getShortId());
        TranscriptRenderer.Rendered rendered = TranscriptRenderer.render(id, shortId, includeNotes, entries);
        this.events.append(CaseDrafts.transcriptExported(id, chosen, rendered.entries(), includeNotes, CaseActor.of(actor.attribution()),
                this.clock.millis()));
        boolean json = chosen.equals("json");
        String fileName = "transcript-" + shortId.replace("#", "") + (json ? ".json" : ".txt");
        return new CaseConversationDTO.Transcript(chosen, fileName, json ? "application/json" : "text/plain; charset=utf-8", rendered.entries(),
                json ? rendered.json() : rendered.text());
    }

    private record Checked(MessageThread.Message message, CaseConversationDTO.Error error) {
    }

    /** The message when {@code actor} may correct it, else why not. */
    private Checked checkEditable(PunishmentEntity punishment, UUID messageId, Actor actor) {
        List<CaseEventRecord> versions = this.reader.subject(punishment.getIdentifier(), messageId.toString());
        List<MessageThread.Message> thread = MessageThread.of(versions);
        if (thread.isEmpty()) {
            return new Checked(null, error(CaseErrorKind.NOT_FOUND, "there is no such message"));
        }
        MessageThread.Message message = thread.getFirst();
        if (message.redacted()) {
            return new Checked(null, error(CaseErrorKind.CONFLICT, "the message was redacted"));
        }
        if (message.deleted()) {
            return new Checked(null, error(CaseErrorKind.CONFLICT, "the message was deleted"));
        }
        if (message.authorKind() != MessageAuthorKind.STAFF) {
            return new Checked(null, error(CaseErrorKind.FORBIDDEN, "only staff messages can be corrected; a player's words stay until they are redacted"));
        }
        CaseEventRecord added = message.first();
        boolean author = added.actorTrust() == ActorTrust.VERIFIED && actor.oid().equals(added.actorOid());
        if (!author && !actor.admin()) {
            return new Checked(null, error(CaseErrorKind.FORBIDDEN, "only the author of a message or an admin may correct it"));
        }
        return new Checked(message, null);
    }

    private CaseConversationDTO store(PunishmentEntity punishment, CaseEventDraft draft, boolean promoteWaiting, CaseActor by, long now) {
        List<CaseChanges.Appended> appended = this.changes.appendMessage(draft, promoteWaiting);
        String messageId = draft.subjectId();
        if (appended.isEmpty()) {
            long seq = this.reader.subject(punishment.getIdentifier(), messageId).stream()
                    .filter(event -> event.type().equals(CaseEventPayload.MESSAGE_ADDED)).mapToLong(CaseEventRecord::seq).findFirst().orElse(0L);
            return new CaseConversationDTO.MessageResponse(messageId, seq, true);
        }
        CaseEventPayload.MessageAdded added = (CaseEventPayload.MessageAdded) draft.payload();
        Map<String, Object> change = new HashMap<>();
        change.put("messageId", messageId);
        change.put("authorKind", added.authorKind().name());
        change.put("channel", added.channel().name());
        change.put("visibility", draft.visibility());
        this.publisher.publish(punishment, appended.getFirst(), by, now, change);
        for (CaseChanges.Appended status : appended.subList(1, appended.size())) {
            this.publisher.publish(punishment, status, CaseActor.system(), now, Map.of("from", "WAITING", "to", "IN_REVIEW"));
        }
        return new CaseConversationDTO.MessageResponse(messageId, appended.getFirst().seq(), false);
    }

    private static Map<String, Object> messageChange(MessageThread.Message message, String visibility) {
        Map<String, Object> change = new HashMap<>();
        change.put("messageId", message.messageId());
        change.put("authorKind", message.authorKind().name());
        change.put("channel", channelOf(message).name());
        change.put("visibility", visibility);
        return change;
    }

    private static MessageChannel channelOf(MessageThread.Message message) {
        return message.first().payload() instanceof CaseEventPayload.MessageAdded added ? added.channel() : MessageChannel.PANEL;
    }

    private CaseEventRecord lastOfType(String punishmentIdentifier, String subjectId, String type) {
        return this.reader.subject(punishmentIdentifier, subjectId).stream().filter(event -> event.type().equals(type)).reduce((a, b) -> b)
                .orElseThrow(() -> new IllegalStateException("the " + type + " event of " + subjectId + " was not stored"));
    }

    private CaseConversationDTO.Error checkText(@Nullable String text) {
        String problem = MessageRules.check(text, this.banIds);
        return problem == null ? null : error(CaseErrorKind.VALIDATION, problem);
    }

    private static CaseConversationDTO.Error checkKey(@Nullable String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            return error(CaseErrorKind.VALIDATION, "the Idempotency-Key is 1 to " + MAX_IDEMPOTENCY_KEY + " letters, digits, dots, dashes, colons or underscores");
        }
        return null;
    }

    private static CaseConversationDTO.Error error(CaseReference.Found found) {
        return error(found.kind(), found.message());
    }

    private static CaseConversationDTO.Error error(CaseErrorKind kind, String message) {
        return new CaseConversationDTO.Error(message, kind);
    }
}
