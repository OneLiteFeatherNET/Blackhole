package net.onelitefeather.blackhole.backend.publicapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;

import java.util.UUID;

/**
 * The player's chat files: a staged upload, a file to download, a removed staged file, or an error. Every error carries a short
 * code the portal maps to a message, never prose.
 */
@Schema(description = "A chat file of the appeal conversation")
@Serdeable
public sealed interface PublicAttachmentDTO {

    /**
     * A file that was scanned and staged. It is visible to nobody until a message names its id in {@code attachmentIds}.
     *
     * @param attachmentId the id to send in a message
     * @param name         the sanitised display name
     * @param contentType  the type detected from the bytes
     * @param size         the stored size in bytes
     * @param width        the pixel width of an image
     * @param height       the pixel height of an image
     */
    @Schema(name = "PublicAttachmentStaged")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Serdeable
    @ReflectiveAccess
    record Staged(
            @NonNull UUID attachmentId,
            @NonNull String name,
            @NonNull String contentType,
            long size,
            @Nullable Integer width,
            @Nullable Integer height
    ) implements PublicAttachmentDTO {
    }

    /**
     * A stored file ready to stream. Never serialised: the controller turns it into the file response with the safe headers.
     *
     * @param download the opened file; the caller closes its stream
     */
    @Schema(hidden = true)
    record Content(EvidenceDTO.Download download) implements PublicAttachmentDTO {
    }

    /** A staged file the player removed. */
    @Schema(hidden = true)
    record Deleted() implements PublicAttachmentDTO {
    }

    /**
     * Why a file request failed.
     *
     * @param code the stable code: {@code NO_APPEAL}, {@code QUOTA}, {@code TOO_LARGE}, {@code UNSUPPORTED_TYPE}, {@code FILE_FLAGGED},
     *             {@code FILE_ACTIVE_CONTENT}, {@code INVALID_FILE}, {@code NOT_FOUND}, {@code FORBIDDEN}, {@code UNAVAILABLE}
     * @param kind the status it maps to
     */
    @Schema(name = "PublicAttachmentError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @NonNull PublicErrorKind kind) implements PublicAttachmentDTO, PublicError {

        @Override
        public @Nullable String availableFrom() {
            return null;
        }
    }
}
