package net.onelitefeather.blackhole.backend.publicapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The conversation as the player reads it and the message the player posts. There is no staff name, no
 * visibility, no channel and no identifier of another case.
 */
@Schema(description = "The conversation between the player and the team")
@Serdeable
public sealed interface PublicConversationDTO {

    /** Who wrote an entry. Staff are never named. */
    @Serdeable
    enum Author {
        PLAYER, STAFF, SYSTEM
    }

    /**
     * @param seq       the order and the paging cursor
     * @param occurredAt an ISO instant
     * @param kind      {@code MESSAGE}, or a marker: {@code APPEAL_FILED}, {@code APPEAL_DECIDED}, {@code FROZEN}, {@code FORFEITED}
     * @param messageId the message, the same across its edits; null for a marker
     * @param text      the current text with every edit folded in, as the Markdown source that was written; null for a marker and for a removed message
     * @param blocks    the sanitised block tree parsed from {@code text} (nodes {@code paragraph}, {@code text}, {@code br}, {@code strong}, {@code em},
     *                  {@code del}, {@code code}, {@code code_block}, {@code list}, {@code item}, {@code quote} and {@code link} with an http(s)
     *                  {@code href} and {@code external}); null when there is no text. Draw it as text nodes and elements, never as HTML.
     * @param removed   whether the message was deleted or redacted
     */
    @Schema(name = "PublicConversationEntry")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Entry(
            long seq, @NonNull String occurredAt, @NonNull String kind, @NonNull Author author, @Nullable String messageId,
            @Nullable String text, @Nullable List<Map<String, Object>> blocks, boolean removed,
            @Nullable List<Attachment> attachments, @Nullable Share share
    ) {
    }

    /**
     * A chat file a message sent. Download it with {@code GET /public/attachments/{attachmentId}}.
     *
     * @param provenance {@code PLAYER} (the player's own upload, unverified) or {@code STAFF} (the team's)
     */
    @Schema(name = "PublicConversationAttachment")
    @Serdeable
    @ReflectiveAccess
    record Attachment(
            @NonNull String attachmentId, @NonNull String name, @NonNull String contentType, long size,
            @Nullable Integer width, @Nullable Integer height, @NonNull String provenance
    ) {
    }

    /**
     * A share of evidence with the player. An image is downloaded by its {@code shareId}; an excerpt shows its {@code lines}.
     *
     * @param shareId   the share id
     * @param mode      {@code FILE} (an image) or {@code EXCERPT} (chat lines)
     * @param state     {@code ACTIVE}, {@code WITHDRAWN} (revoked) or {@code UNAVAILABLE} (the evidence is gone)
     * @param lineCount the number of lines of an excerpt; null for an image
     * @param lines     the excerpt lines, speakers relabelled ("You", "Player 2"), offsets from the first line; null unless ACTIVE excerpt
     */
    @Schema(name = "PublicConversationShare")
    @Serdeable
    @ReflectiveAccess
    record Share(
            @NonNull String shareId, @Nullable String mode, @NonNull String state, @Nullable Integer lineCount,
            @Nullable List<ShareLine> lines
    ) {
    }

    /** One line of an excerpt: a relabelled speaker, an offset in seconds from the first line, and the plain text. */
    @Schema(name = "PublicConversationShareLine")
    @Serdeable
    @ReflectiveAccess
    record ShareLine(@NonNull String speaker, @Nullable Long offsetSeconds, @NonNull String text) {
    }

    /**
     * @param cursor the {@code seq} to pass as {@code cursor} next time; null while the conversation is empty
     */
    @Schema(name = "PublicConversation")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Page(@NonNull List<Entry> entries, @Nullable Long cursor) implements PublicConversationDTO {
    }

    /**
     * Posts a message to the team.
     *
     * @param text          1 to 2000 characters of Markdown (a small subset, see the conversation entry), without a UUID, an IP address or a
     *                      Ban ID; may be absent when the message has at least one file
     * @param attachmentIds up to five files uploaded with {@code POST /public/attachments} for this appeal, each sent once
     */
    @Schema(name = "PublicMessageRequest")
    @Serdeable
    @ReflectiveAccess
    record PostRequest(@Nullable String text, @Nullable List<UUID> attachmentIds) implements PublicConversationDTO {
    }

    /**
     * @param repeated whether the same {@code Idempotency-Key} had posted this message before and nothing was added
     */
    @Schema(name = "PublicMessage")
    @Serdeable
    @ReflectiveAccess
    record Posted(@NonNull String messageId, boolean repeated) implements PublicConversationDTO {
    }

    @Schema(name = "PublicConversationError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @Nullable String availableFrom, @NonNull PublicErrorKind kind) implements PublicConversationDTO, PublicError {
    }
}
