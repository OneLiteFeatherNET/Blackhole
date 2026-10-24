package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayloads;
import net.onelitefeather.blackhole.backend.casetimeline.MessageContent;
import net.onelitefeather.blackhole.backend.casetimeline.MessageRules;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.evidence.share.ExcerptRenderer;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PlayerSeenMarker;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.PublicConversationReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicConversationDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the conversation of the session's own case. A leaked Ban ID (scope {@code LIMITED}) reads
 * nothing and writes nothing: whoever holds it must not read the player's statements, and the ticket is
 * frozen on the player side. The punishment, and with it the case and the appeal, come from the session.
 */
@Singleton
public class PublicConversationService {

    private static final ObjectMapper FILES = new ObjectMapper();

    private final PublicConversationReader reader;
    private final PublicAppealReader appeals;
    private final CaseConversationService conversation;
    private final BanIdService banIds;
    private final int maxLength;
    private final int pageSize;
    private final MessageContent content;
    private final PlayerSeenMarker seen;

    public PublicConversationService(
            PublicConversationReader reader, PublicAppealReader appeals, CaseConversationService conversation, BanIdService banIds,
            @Value("${blackhole.public.message.max-length:2000}") int maxLength,
            @Value("${blackhole.public.conversation.page-size:200}") int pageSize, MessageContent content, PlayerSeenMarker seen
    ) {
        this.reader = reader;
        this.appeals = appeals;
        this.conversation = conversation;
        this.banIds = banIds;
        this.maxLength = Math.min(maxLength, MessageRules.MAX_MESSAGE);
        this.pageSize = Math.max(1, pageSize);
        this.content = content;
        this.seen = seen;
    }

    /**
     * The entries after a cursor, in {@code seq} order. Reading them moves the player's seen marker to the highest seq returned
     * (one statement, never backwards), which is what staff see as "Seen".
     *
     * @param cursor the last {@code seq} the portal has seen, or null for the start
     * @return the {@link PublicConversationDTO.Page}, or 403 {@code FORBIDDEN} for a leaked Ban ID
     */
    public PublicConversationDTO read(PlayerSession session, @Nullable Long cursor) {
        if (session.scope() == PlayerScope.LIMITED) {
            return error("FORBIDDEN", PublicErrorKind.FORBIDDEN);
        }
        PublicConversationReader.Page page = this.reader.read(session.punishmentIdentifier(), cursor, this.pageSize);
        List<PublicConversationDTO.Entry> entries = page.entries().stream()
                .map(entry -> new PublicConversationDTO.Entry(entry.seq(), Instant.ofEpochMilli(entry.occurredAt()).toString(), entry.kind(),
                        PublicConversationDTO.Author.valueOf(entry.author()), entry.messageId(), entry.text(),
                        entry.text() == null ? null : this.content.blocks(entry.text()), entry.removed(), attachmentsOf(entry), shareOf(entry)))
                .toList();
        if (page.cursor() != null && !page.entries().isEmpty()) {
            this.seen.moveTo(session.punishmentIdentifier(), page.cursor());
        }
        return new PublicConversationDTO.Page(entries, page.cursor());
    }

    /**
     * Posts a message to the team in the open appeal. It may send staged files of this appeal (at most five); a message with files
     * may have no text.
     *
     * @param idempotencyKey makes a double submit a no-op
     * @return the {@link PublicConversationDTO.Posted}, or an error: 400 / 422 for the text or a file id that is not usable,
     *         409 {@code FROZEN} (the Ban ID is leaked), {@code NO_APPEAL} (nothing is open) or {@code FILES_UNAVAILABLE} (a file was
     *         sent already or is gone)
     */
    public PublicConversationDTO post(PlayerSession session, @Nullable PublicConversationDTO.PostRequest request, @Nullable String idempotencyKey) {
        boolean leaked = session.scope() == PlayerScope.LIMITED;
        Optional<PublicAppealReader.Row> open = this.appeals.list(session.punishmentIdentifier()).stream().filter(PublicAppealReader.Row::open).findFirst();
        if (leaked) {
            return error(open.isPresent() ? "FROZEN" : "NO_APPEAL", PublicErrorKind.CONFLICT);
        }
        List<UUID> attachmentIds = request == null || request.attachmentIds() == null ? List.of() : request.attachmentIds();
        String rawText = request == null ? null : request.text();
        boolean textGiven = rawText != null && !rawText.isBlank();
        String text = "";
        if (textGiven || attachmentIds.isEmpty()) {
            PublicText.Checked checked = PublicText.check(rawText, this.maxLength, this.banIds);
            if (!checked.ok()) {
                return error(checked.code(), checked.failure());
            }
            text = checked.text();
        }
        if (open.isEmpty()) {
            return error("NO_APPEAL", PublicErrorKind.CONFLICT);
        }
        CaseConversationDTO result = this.conversation.postPlayerMessage(session.punishmentIdentifier(), open.get().identifier(), text, idempotencyKey,
                attachmentIds);
        return switch (result) {
            case CaseConversationDTO.MessageResponse posted -> new PublicConversationDTO.Posted(posted.messageId(), posted.repeated());
            case CaseConversationDTO.Error failed -> switch (failed.kind()) {
                case VALIDATION -> error("VALIDATION", PublicErrorKind.VALIDATION);
                case CONFLICT -> attachmentIds.isEmpty() ? error("FROZEN", PublicErrorKind.CONFLICT)
                        : error("FILES_UNAVAILABLE", PublicErrorKind.CONFLICT);
                case NOT_FOUND -> error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
                default -> error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
            };
            default -> error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
        };
    }

    private static PublicConversationDTO.Error error(String code, PublicErrorKind kind) {
        return new PublicConversationDTO.Error(code, null, kind);
    }

    /** The files a message sent, as the portal lists them; null when the entry has none. Nothing but the id, name and type leaves. */
    private static @Nullable List<PublicConversationDTO.Attachment> attachmentsOf(PublicConversationReader.Entry entry) {
        if (entry.attachmentsJson() == null) {
            return null;
        }
        List<PublicConversationDTO.Attachment> files = new ArrayList<>();
        try {
            for (Map<String, Object> file : FILES.readValue(entry.attachmentsJson(), new TypeReference<List<Map<String, Object>>>() { })) {
                if (file.get("attachmentId") instanceof String id) {
                    files.add(new PublicConversationDTO.Attachment(id, text(file.get("name")), text(file.get("contentType")),
                            file.get("size") instanceof Number size ? size.longValue() : 0L,
                            file.get("width") instanceof Number width ? width.intValue() : null,
                            file.get("height") instanceof Number height ? height.intValue() : null,
                            text(file.get("provenance")).isEmpty() ? "PLAYER" : text(file.get("provenance"))));
                }
            }
        } catch (JsonProcessingException malformed) {
            return null;
        }
        return files.isEmpty() ? null : files;
    }

    /** The share card of a share entry; the state is the share as it stands when the player reads it. */
    private static @Nullable PublicConversationDTO.Share shareOf(PublicConversationReader.Entry entry) {
        PublicConversationReader.Share share = entry.share();
        if (share == null) {
            return null;
        }
        String state = share.withdrawn() ? "WITHDRAWN" : share.mode() == null || !share.evidencePresent() ? "UNAVAILABLE" : "ACTIVE";
        boolean excerpt = "EXCERPT".equals(share.mode());
        List<PublicConversationDTO.ShareLine> lines = null;
        if (excerpt && "ACTIVE".equals(state)) {
            List<ExcerptRenderer.Line> rendered = ExcerptRenderer.render(ExcerptRenderer.linesOf(CaseEventPayloads.parse(share.evidencePayload())),
                    share.selection(), share.appellant() == null ? "" : share.appellant());
            lines = rendered.stream().map(line -> new PublicConversationDTO.ShareLine(line.speaker(), line.offsetSeconds(), line.text())).toList();
        }
        return new PublicConversationDTO.Share(share.shareId(), share.mode(), state, excerpt ? share.selection().size() : null, lines);
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }
}
