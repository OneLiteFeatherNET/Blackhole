package net.onelitefeather.blackhole.backend.discord.ingest;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPublisher;
import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAttachment;
import net.onelitefeather.blackhole.backend.discord.attachment.DiscordAttachmentService;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;
import net.onelitefeather.blackhole.backend.casetimeline.MessageEmbed;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.discord.DiscordTicketProperties;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO.Code;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicket;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.Participant;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records what is written in a ticket channel on the case: every message of the player, of staff and of other bots, its edits and its
 * deletions, so nothing lives only in Discord. Each item is one SQL statement (event, link and cursor together, {@link DiscordIngestStore});
 * a replay, an overlapping catch-up or a duplicate gateway event inserts nothing twice.
 *
 * <p>The bot posts a message in its current state and the backend decides whether it is new, an edit (the text differs from the latest known)
 * or already known. Text is the display text (mentions resolved), kept complete: the PII guard is for what our own routes accept, not for the
 * record. Attachments are metadata only. A Discord user id is stored only on the author's {@code case_participant}, never in an event.</p>
 */
@Singleton
public class DiscordIngestService {

    private static final Pattern SNOWFLAKE = Pattern.compile("^\\d{1,19}$");
    private static final Pattern USER_ID = Pattern.compile("^\\d{15,20}$");
    private static final int MAX_NAME = 128;
    private static final int MAX_ATTACHMENTS = 20;
    private static final int MAX_EMBEDS = 10;
    private static final int MAX_FIELD = 255;

    private final DiscordTicketStore tickets;
    private final DiscordIngestStore ingest;
    private final DiscordLinkRepository links;
    private final PunishmentRepository punishments;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final DiscordTicketProperties properties;
    private final DiscordAttachmentService files;
    private final ChatFiles chatFiles;

    public DiscordIngestService(
            DiscordTicketStore tickets, DiscordIngestStore ingest, DiscordLinkRepository links, PunishmentRepository punishments,
            DomainEventPublisher events, Clock clock, DiscordTicketProperties properties, DiscordAttachmentService files, ChatFiles chatFiles
    ) {
        this.files = files;
        this.chatFiles = chatFiles;
        this.tickets = tickets;
        this.ingest = ingest;
        this.links = links;
        this.punishments = punishments;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Ingests a batch. Messages are handled in Discord id order (the order they were written); a message that fails does not stop the
     * rest of the batch from being validated first, so a malformed batch records nothing.
     *
     * @return {@link DiscordTicketDTO.Ingested}, or an error: 400 a malformed batch, 404, 409 when the channel is already deleted
     */
    public DiscordTicketDTO ingest(UUID ticketId, DiscordTicketDTO.IngestRequest request, Actor bot) {
        DiscordTicket ticket = this.tickets.ticket(ticketId).orElse(null);
        if (ticket == null) {
            return error(ErrorKind.NOT_FOUND, "there is no such ticket", null);
        }
        if (ticket.state() == TicketState.DELETED) {
            return error(ErrorKind.CONFLICT, "the channel of this ticket is deleted", Code.INVALID_STATE);
        }
        List<DiscordTicketDTO.IngestItem> items = request.items() == null ? List.of() : request.items();
        List<String> deletes = request.deletes() == null ? List.of() : request.deletes();
        List<String> present = request.presentMessageIds() == null ? List.of() : request.presentMessageIds();
        if (items.size() > this.properties.maxBatchItems() || deletes.size() > this.properties.maxBatchItems()
                || present.size() > 50 * this.properties.maxBatchItems()) {
            return error(ErrorKind.VALIDATION, "a batch carries at most " + this.properties.maxBatchItems() + " messages", null);
        }
        Optional<Valid> valid = validate(items, deletes, present);
        if (valid.isEmpty()) {
            return error(ErrorKind.VALIDATION, "a message needs a Discord message id, a Discord author id and a time", null);
        }

        PunishmentEntity punishment = this.punishments.findById(ticket.punishmentIdentifier()).orElse(null);
        Participant bound = this.tickets.participant(ticket.ownerParticipantId()).orElse(null);
        Counts counts = new Counts();
        long now = this.clock.millis();

        for (Parsed item : valid.get().items()) {
            handle(ticket, bound, punishment, item, bot, counts, now);
        }
        for (long id : valid.get().deletes()) {
            delete(ticket, punishment, id, false, bot, counts, now);
        }
        if (request.complete()) {
            for (long missing : this.ingest.missing(ticket.id(), valid.get().present())) {
                delete(ticket, punishment, missing, true, bot, counts, now);
            }
        }
        long cursor = this.tickets.ticket(ticketId).map(DiscordTicket::ingestedCursor).orElse(ticket.ingestedCursor());
        return new DiscordTicketDTO.Ingested(counts.added, counts.edited, counts.deleted, counts.unchanged, Long.toString(cursor));
    }

    private void handle(DiscordTicket ticket, @Nullable Participant bound, @Nullable PunishmentEntity punishment, Parsed item, Actor bot,
                        Counts counts, long now) {
        Optional<DiscordIngestStore.Link> link = this.ingest.link(ticket.id(), item.messageId());
        String text = item.text();
        String hash = hash(text);
        if (link.isPresent()) {
            DiscordIngestStore.Link known = link.get();
            if (known.deleted() || !"INBOUND".equals(known.direction()) || hash.equals(known.textHash())) {
                counts.unchanged++;
                return;
            }
            long at = item.editedAt() != null ? item.editedAt() : now;
            String key = "discord:" + ticket.channelKey() + ":" + item.messageId() + ":e:" + hash;
            Optional<CaseChanges.Appended> edited = this.ingest.edit(ticket.id(), ticket.punishmentIdentifier(), item.messageId(), hash, text, at, key);
            if (edited.isPresent()) {
                counts.edited++;
                publish(punishment, edited.get(), CaseActor.system(), at, Map.of("messageId", known.messageId().toString(),
                        "channel", MessageChannel.DISCORD.name()));
            } else {
                counts.unchanged++;
            }
            return;
        }

        Optional<UUID> staffOid = item.staffRole() && !item.bot() ? linkedStaff(item.authorId()) : Optional.empty();
        AuthorResolver.Resolution who = AuthorResolver.resolve(item.authorId(), item.bot(), item.staffRole(),
                bound == null ? null : bound.discordUserId(), staffOid.orElse(null));
        String participantId = null;
        if (who.participantKind() != null) {
            participantId = who.boundPlayer() && bound != null ? bound.id().toString()
                    : this.tickets.ensureParticipant(ticket.punishmentIdentifier(), who.participantKind(), item.authorId(), item.authorName(),
                            item.bot(), now).id().toString();
        }
        UUID messageId = messageId(ticket, item.messageId());
        CaseActor actor = new CaseActor(who.trust(), who.staffOid(), bot.oid(), null);
        EvidenceProvenance expected = who.kind() == MessageAuthorKind.PLAYER ? EvidenceProvenance.PLAYER
                : who.kind() == MessageAuthorKind.STAFF ? EvidenceProvenance.STAFF : null;
        List<MessageAttachment> attachments = attachments(ticket, item.attachments(), expected);
        CaseEventPayload.MessageAdded payload = new CaseEventPayload.MessageAdded(messageId.toString(), ticket.appealId().toString(), who.kind(),
                participantId, MessageChannel.DISCORD, text, attachments, item.embeds());
        CaseEventDraft draft = new CaseEventDraft(ticket.punishmentIdentifier(), payload, item.createdAt(), CaseEventOrigin.LIVE, actor,
                messageId.toString(), "discord:" + ticket.channelKey() + ":" + item.messageId(), who.visibility());
        List<CaseChanges.Appended> appended = this.ingest.add(ticket.id(), item.messageId(), hash, draft, who.kind() == MessageAuthorKind.PLAYER, now);
        if (appended.isEmpty()) {
            counts.unchanged++;
            return;
        }
        counts.added++;
        List<UUID> acceptedIds = attachments.stream().filter(a -> a.attachmentId() != null).map(a -> UUID.fromString(a.attachmentId())).toList();
        if (!acceptedIds.isEmpty()) {
            this.chatFiles.bound(ticket.punishmentIdentifier(), acceptedIds);
        }
        Map<String, Object> change = new HashMap<>();
        change.put("messageId", messageId.toString());
        change.put("authorKind", who.kind().name());
        change.put("channel", MessageChannel.DISCORD.name());
        change.put("visibility", who.visibility());
        publish(punishment, appended.getFirst(), actor, now, change);
        for (CaseChanges.Appended status : appended.subList(1, appended.size())) {
            publish(punishment, status, CaseActor.system(), now, Map.of("from", "WAITING", "to", "IN_REVIEW"));
        }
    }

    private void delete(DiscordTicket ticket, @Nullable PunishmentEntity punishment, long discordMessageId, boolean detectedAtCatchup, Actor bot,
                        Counts counts, long now) {
        String key = "discord:" + ticket.channelKey() + ":" + discordMessageId + ":d";
        Optional<DiscordIngestStore.Link> link = this.ingest.link(ticket.id(), discordMessageId);
        Optional<CaseChanges.Appended> deleted;
        if (link.isEmpty()) {
            deleted = detectedAtCatchup ? Optional.empty()
                    : this.ingest.deleteUnknown(ticket.id(), ticket.punishmentIdentifier(), discordMessageId, messageId(ticket, discordMessageId), now, key,
                            bot.oid());
        } else if (link.get().deleted() || !"INBOUND".equals(link.get().direction())) {
            deleted = Optional.empty();
        } else {
            deleted = this.ingest.delete(ticket.id(), ticket.punishmentIdentifier(), discordMessageId, now, detectedAtCatchup, key);
        }
        if (deleted.isPresent()) {
            counts.deleted++;
            publish(punishment, deleted.get(), CaseActor.system(), now, Map.of("channel", MessageChannel.DISCORD.name()));
        } else {
            counts.unchanged++;
        }
    }

    private Optional<UUID> linkedStaff(String discordUserId) {
        return this.links.findByKindAndDiscordUserId(DiscordLinkKind.STAFF, discordUserId).map(DiscordLinkEntity::getSubject).map(subject -> {
            try {
                return UUID.fromString(subject);
            } catch (IllegalArgumentException notAnOid) {
                return null;
            }
        });
    }

    private void publish(@Nullable PunishmentEntity punishment, CaseChanges.Appended event, CaseActor by, long at, Map<String, Object> change) {
        if (punishment != null) {
            new CaseEventPublisher(this.events).publish(punishment, event, by, at, change);
        }
    }

    // ---- validation and mapping ----

    private record Parsed(long messageId, String authorId, @Nullable String authorName, boolean bot, boolean staffRole, long createdAt,
                          @Nullable Long editedAt, String text, List<DiscordTicketDTO.IngestAttachment> attachments, List<MessageEmbed> embeds) {
    }

    private record Valid(List<Parsed> items, long[] deletes, long[] present) {
    }

    private Optional<Valid> validate(List<DiscordTicketDTO.IngestItem> items, List<String> deletes, List<String> present) {
        List<Parsed> parsed = new ArrayList<>();
        for (DiscordTicketDTO.IngestItem item : items) {
            if (item == null || !validId(item.discordMessageId()) || item.authorDiscordUserId() == null
                    || !USER_ID.matcher(item.authorDiscordUserId()).matches() || item.createdAt() <= 0) {
                return Optional.empty();
            }
            String text = item.text() == null ? "" : item.text();
            if (text.length() > this.properties.maxTextLength()) {
                text = text.substring(0, this.properties.maxTextLength());
            }
            String name = item.authorName() == null || item.authorName().isBlank() ? null : cut(item.authorName().strip(), MAX_NAME);
            parsed.add(new Parsed(Long.parseLong(item.discordMessageId()), item.authorDiscordUserId(), name, item.bot(), item.hasStaffRole(),
                    item.createdAt(), item.editedAt(), text, item.attachments() == null ? List.of() : item.attachments(), embeds(item.embeds())));
        }
        parsed.sort(Comparator.comparingLong(Parsed::messageId));
        long[] deleteIds = new long[deletes.size()];
        for (int i = 0; i < deleteIds.length; i++) {
            if (!validId(deletes.get(i))) {
                return Optional.empty();
            }
            deleteIds[i] = Long.parseLong(deletes.get(i));
        }
        long[] presentIds = new long[present.size()];
        for (int i = 0; i < presentIds.length; i++) {
            if (!validId(present.get(i))) {
                return Optional.empty();
            }
            presentIds[i] = Long.parseLong(present.get(i));
        }
        return Optional.of(new Valid(parsed, deleteIds, presentIds));
    }

    private static boolean validId(@Nullable String id) {
        if (id == null || !SNOWFLAKE.matcher(id).matches()) {
            return false;
        }
        try {
            return Long.parseLong(id) > 0;
        } catch (NumberFormatException tooBig) {
            return false;
        }
    }

    /**
     * The attachments of a message as they are stored. A file that names an accepted chat file of this ticket, with the provenance the
     * author has ({@code expected}), is stored as that file; anything else is metadata only. The Discord attachment id is never stored.
     */
    private List<MessageAttachment> attachments(DiscordTicket ticket, List<DiscordTicketDTO.IngestAttachment> raw,
                                                @Nullable EvidenceProvenance expected) {
        List<MessageAttachment> found = new ArrayList<>();
        for (DiscordTicketDTO.IngestAttachment attachment : raw) {
            if (attachment == null || found.size() >= MAX_ATTACHMENTS) {
                continue;
            }
            String kind = MessageAttachment.STICKER.equals(attachment.kind()) ? MessageAttachment.STICKER : MessageAttachment.FILE;
            String rejected = rejectedCode(attachment.rejected());
            Optional<DiscordTicketDTO.Attachment> accepted = attachment.attachmentId() == null || expected == null
                    || !MessageAttachment.FILE.equals(kind) || rejected != null ? Optional.empty()
                    : parseUuid(attachment.attachmentId()).flatMap(id -> this.files.accepted(ticket.id(), ticket.punishmentIdentifier(), id, expected));
            if (accepted.isPresent()) {
                DiscordTicketDTO.Attachment file = accepted.get();
                found.add(new MessageAttachment(MessageAttachment.FILE, null, file.name(), file.contentType(), file.size(), file.width(),
                        file.height(), file.attachmentId(), file.provenance()));
            } else {
                found.add(new MessageAttachment(kind, null, cutOrNull(attachment.name(), MAX_FIELD), cutOrNull(attachment.contentType(), MAX_FIELD),
                        attachment.size(), attachment.width(), attachment.height(), null, null, rejected));
            }
        }
        return found;
    }

    private static Optional<UUID> parseUuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** The reason a file was not accepted, when it is one of the codes the upload answers; {@code null} otherwise. */
    private static @Nullable String rejectedCode(@Nullable String code) {
        if (code == null) {
            return null;
        }
        for (DiscordTicketDTO.Code known : DiscordTicketDTO.Code.values()) {
            if (known.name().equals(code)) {
                return code;
            }
        }
        return null;
    }

    private static List<MessageEmbed> embeds(@Nullable List<DiscordTicketDTO.IngestEmbed> raw) {
        if (raw == null) {
            return List.of();
        }
        List<MessageEmbed> found = new ArrayList<>();
        for (DiscordTicketDTO.IngestEmbed embed : raw) {
            if (embed == null || embed.type() == null || found.size() >= MAX_EMBEDS) {
                continue;
            }
            found.add(new MessageEmbed(cut(embed.type(), 32), cutOrNull(embed.host(), MAX_FIELD)));
        }
        return found;
    }

    /** The case message of a Discord message: derived from the channel and message id, so a replay names the same message. */
    static UUID messageId(DiscordTicket ticket, long discordMessageId) {
        return UUID.nameUUIDFromBytes(("discord|" + ticket.id() + "|" + discordMessageId).getBytes(StandardCharsets.UTF_8));
    }

    /** The hash that tells an edit from a repeat: SHA-256 of the text (only a different text is an edit). */
    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static @Nullable String cutOrNull(@Nullable String text, int max) {
        return text == null ? null : cut(text, max);
    }

    private static DiscordTicketDTO.Error error(ErrorKind kind, String message, @Nullable Code code) {
        return DiscordTicketDTO.Error.of(message, kind, code);
    }

    private static final class Counts {
        int added;
        int edited;
        int deleted;
        int unchanged;
    }
}
