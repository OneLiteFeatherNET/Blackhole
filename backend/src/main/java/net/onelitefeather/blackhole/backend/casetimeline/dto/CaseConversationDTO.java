package net.onelitefeather.blackhole.backend.casetimeline.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakReason;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdReissueReason;
import net.onelitefeather.blackhole.backend.casetimeline.NoteRedactionReason;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the conversation, leak and export routes of a case. Staff only. No variant carries a
 * Ban ID, a raw UUID or an IP address; a request never names a visibility, an author or a channel: the
 * server sets them.
 */
@Schema(description = "The conversation of a case: messages, the Ban ID leak rule and the transcript export")
@Serdeable
public sealed interface CaseConversationDTO {

    /**
     * Posts or corrects a message to the player. Any visibility, author or channel in the body is ignored.
     *
     * @param text          the Markdown source, 1 to 2000 characters, without a UUID, an IP address or a Ban ID; may be left out
     *                      when the message carries at least one file
     * @param attachmentIds files uploaded with {@code POST /case/{reference}/attachments} that this message sends, at most 5; each
     *                      must be staged for this case and not used before. A staff file is visible to the player once the message
     *                      is posted. Ignored when editing: an edit changes the text only
     */
    @Schema(name = "CaseMessageRequest")
    @Serdeable
    @ReflectiveAccess
    record MessageRequest(@Nullable String text, @Nullable List<UUID> attachmentIds) implements CaseConversationDTO {
    }

    /**
     * Redacts a message, or every message of the player.
     *
     * @param reason why, as a code
     */
    @Schema(name = "CaseMessageRedactRequest")
    @Serdeable
    @ReflectiveAccess
    record RedactRequest(@NonNull NoteRedactionReason reason) implements CaseConversationDTO {
    }

    /** Marks the Ban ID of the punishment leaked. */
    @Schema(name = "CaseBanIdLeakRequest")
    @Serdeable
    @ReflectiveAccess
    record LeakRequest(@NonNull BanIdLeakReason reason) implements CaseConversationDTO {
    }

    /** Reissues the Ban ID of the punishment. */
    @Schema(name = "CaseBanIdReissueRequest")
    @Serdeable
    @ReflectiveAccess
    record ReissueRequest(@NonNull BanIdReissueReason reason) implements CaseConversationDTO {
    }

    /**
     * The result of posting, editing or deleting a message.
     *
     * @param messageId the id of the message, the same across its edits
     * @param seq       the sequence number of the event that was appended, or of the original when the request was a repeat
     * @param repeated  whether the same {@code Idempotency-Key} had already posted this message and nothing was appended
     */
    @Schema(name = "CaseMessageResponse")
    @Serdeable
    @ReflectiveAccess
    record MessageResponse(@NonNull String messageId, long seq, boolean repeated) implements CaseConversationDTO {
    }

    /**
     * The result of a message redaction.
     *
     * @param redactedVersions how many versions lost their text
     */
    @Schema(name = "CaseMessageRedactResponse")
    @Serdeable
    @ReflectiveAccess
    record RedactResponse(@NonNull String messageId, int redactedVersions) implements CaseConversationDTO {
    }

    /**
     * The result of erasing the player's side of the conversation.
     *
     * @param redactedMessages how many player messages were redacted now
     */
    @Schema(name = "CaseTranscriptEraseResponse")
    @Serdeable
    @ReflectiveAccess
    record EraseResponse(int redactedMessages) implements CaseConversationDTO {
    }

    /**
     * The result of marking a leak.
     *
     * @param changed {@code false} when the Ban ID was already marked leaked and nothing was appended
     */
    @Schema(name = "CaseBanIdLeakResponse")
    @Serdeable
    @ReflectiveAccess
    record LeakResponse(boolean changed) implements CaseConversationDTO {
    }

    /**
     * The result of a reissue. The new Ban ID is not in it: staff reveal it with the reveal route.
     *
     * @param generation the generation of the new Ban ID
     */
    @Schema(name = "CaseBanIdReissueResponse")
    @Serdeable
    @ReflectiveAccess
    record ReissueResponse(int generation) implements CaseConversationDTO {
    }

    /**
     * An exported transcript: the file the controller serves as an attachment.
     *
     * @param format      {@code json} or {@code text}
     * @param fileName    the suggested file name
     * @param contentType the media type of {@code content}
     * @param entries     how many entries the transcript lists
     * @param content     the file
     */
    @Schema(name = "CaseTranscript")
    @Serdeable
    @ReflectiveAccess
    record Transcript(
            @NonNull String format, @NonNull String fileName, @NonNull String contentType, int entries, @NonNull String content
    ) implements CaseConversationDTO {
    }

    @Schema(name = "CaseConversationError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull CaseErrorKind kind) implements CaseConversationDTO, ErrorResponse {
    }
}
