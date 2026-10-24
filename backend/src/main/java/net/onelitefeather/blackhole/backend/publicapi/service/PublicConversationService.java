package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.MessageRules;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.PublicConversationReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicConversationDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the conversation of the session's own case. A leaked Ban ID (scope {@code LIMITED}) reads
 * nothing and writes nothing: whoever holds it must not read the player's statements, and the ticket is
 * frozen on the player side. The punishment, and with it the case and the appeal, come from the session.
 */
@Singleton
public class PublicConversationService {

    private final PublicConversationReader reader;
    private final PublicAppealReader appeals;
    private final CaseConversationService conversation;
    private final BanIdService banIds;
    private final int maxLength;
    private final int pageSize;

    public PublicConversationService(
            PublicConversationReader reader, PublicAppealReader appeals, CaseConversationService conversation, BanIdService banIds,
            @Value("${blackhole.public.message.max-length:2000}") int maxLength,
            @Value("${blackhole.public.conversation.page-size:200}") int pageSize
    ) {
        this.reader = reader;
        this.appeals = appeals;
        this.conversation = conversation;
        this.banIds = banIds;
        this.maxLength = Math.min(maxLength, MessageRules.MAX_MESSAGE);
        this.pageSize = Math.max(1, pageSize);
    }

    /**
     * The entries after a cursor, in {@code seq} order.
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
                        PublicConversationDTO.Author.valueOf(entry.author()), entry.messageId(), entry.text(), entry.removed()))
                .toList();
        return new PublicConversationDTO.Page(entries, page.cursor());
    }

    /**
     * Posts a message to the team in the open appeal.
     *
     * @param idempotencyKey makes a double submit a no-op
     * @return the {@link PublicConversationDTO.Posted}, or an error: 400 / 422 for the text, 409 {@code FROZEN}
     *         (the Ban ID is leaked) or {@code NO_APPEAL} (nothing is open)
     */
    public PublicConversationDTO post(PlayerSession session, @Nullable PublicConversationDTO.PostRequest request, @Nullable String idempotencyKey) {
        boolean leaked = session.scope() == PlayerScope.LIMITED;
        Optional<PublicAppealReader.Row> open = this.appeals.list(session.punishmentIdentifier()).stream().filter(PublicAppealReader.Row::open).findFirst();
        if (leaked) {
            return error(open.isPresent() ? "FROZEN" : "NO_APPEAL", PublicErrorKind.CONFLICT);
        }
        PublicText.Checked text = PublicText.check(request == null ? null : request.text(), this.maxLength, this.banIds);
        if (!text.ok()) {
            return error(text.code(), text.failure());
        }
        if (open.isEmpty()) {
            return error("NO_APPEAL", PublicErrorKind.CONFLICT);
        }
        CaseConversationDTO result = this.conversation.postPlayerMessage(session.punishmentIdentifier(), open.get().identifier(), text.text(), idempotencyKey);
        return switch (result) {
            case CaseConversationDTO.MessageResponse posted -> new PublicConversationDTO.Posted(posted.messageId(), posted.repeated());
            case CaseConversationDTO.Error failed -> switch (failed.kind()) {
                case VALIDATION -> error("VALIDATION", PublicErrorKind.VALIDATION);
                case CONFLICT -> error("FROZEN", PublicErrorKind.CONFLICT);
                case NOT_FOUND -> error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
                default -> error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
            };
            default -> error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
        };
    }

    private static PublicConversationDTO.Error error(String code, PublicErrorKind kind) {
        return new PublicConversationDTO.Error(code, null, kind);
    }
}
