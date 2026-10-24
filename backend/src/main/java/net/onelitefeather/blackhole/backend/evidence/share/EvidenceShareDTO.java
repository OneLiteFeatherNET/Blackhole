package net.onelitefeather.blackhole.backend.evidence.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the evidence sharing routes of a case. Staff only. A share shows one existing evidence item to the appealing player,
 * explicitly, revocably and minimised. No variant carries a Ban ID, an owner hash or a raw player UUID.
 */
@Schema(description = "Sharing one evidence item with the appealing player")
@Serdeable
public sealed interface EvidenceShareDTO {

    /**
     * Shares an item.
     *
     * @param evidenceId         the evidence item of this case: a {@code CHAT_LOG} or an image file
     * @param selection          for a chat capture, the line indexes to show (1 to the configured maximum, default 20); empty for an image
     * @param appellantSpeaker   for a chat capture, the speaker label of the capture that is the appealing player ("You" to the player)
     * @param confirmedMinimised must be {@code true}: the sharer checked the preview and that it shows no other person's personal data
     *                           beyond what the appeal needs (for an image: that it may show other players' names and the player may see it)
     */
    @Schema(name = "EvidenceShareRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull UUID evidenceId,
            @Nullable List<Integer> selection,
            @Nullable String appellantSpeaker,
            boolean confirmedMinimised
    ) implements EvidenceShareDTO {
    }

    /** What the player would see for a selection; changes nothing. */
    @Schema(name = "EvidenceSharePreviewRequest")
    @Serdeable
    @ReflectiveAccess
    record PreviewRequest(
            @NonNull UUID evidenceId,
            @Nullable List<Integer> selection,
            @Nullable String appellantSpeaker
    ) implements EvidenceShareDTO {
    }

    /** Withdraws a share. */
    @Schema(name = "EvidenceShareRevokeRequest")
    @Serdeable
    @ReflectiveAccess
    record RevokeRequest(@NonNull ShareRevokeReason reason) implements EvidenceShareDTO {
    }

    /** One line of a rendered excerpt, as the player sees it. */
    @Schema(name = "EvidenceShareLine")
    @Serdeable
    @ReflectiveAccess
    record Line(@NonNull String speaker, @Nullable Long offsetSeconds, @NonNull String text) {
    }

    /**
     * What the player will see.
     *
     * @param mode        {@code EXCERPT} (lines) or {@code FILE} (an image)
     * @param lines       the excerpt: speakers relabelled, times as offsets, plain text
     * @param contentType for an image, its type
     * @param width       for an image, pixels
     * @param height      for an image, pixels
     */
    @Schema(name = "EvidenceSharePreview")
    @Serdeable
    @ReflectiveAccess
    record Preview(
            @NonNull String mode,
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<Line> lines,
            @Nullable String contentType,
            @Nullable Integer width,
            @Nullable Integer height
    ) implements EvidenceShareDTO {
    }

    /**
     * One share.
     *
     * @param state      {@code ACTIVE}, {@code REVOKED}, or {@code UNAVAILABLE} when the evidence was deleted
     * @param lineCount  the number of shared lines of an excerpt
     * @param repeated   on a create: an identical active share existed already and nothing was added
     * @param sharedBy   the {@code oid} of the staff member who confirmed the share
     */
    @Schema(name = "EvidenceShare")
    @Serdeable
    @ReflectiveAccess
    record Share(
            @NonNull UUID shareId,
            @NonNull UUID evidenceId,
            @NonNull String evidenceKind,
            @NonNull String mode,
            @Nullable Integer lineCount,
            @NonNull String state,
            long sharedAt,
            @NonNull UUID sharedBy,
            @Nullable Long revokedAt,
            @Nullable ShareRevokeReason revokeReason,
            boolean repeated
    ) implements EvidenceShareDTO {
    }

    /** The shares of a case, oldest first. */
    @Schema(name = "EvidenceShareList")
    @Serdeable
    @ReflectiveAccess
    record ShareList(@NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<Share> items) implements EvidenceShareDTO {
    }

    /**
     * @param code a stable code: {@code NOT_SHAREABLE}, {@code NOT_VALID}, {@code NO_APPEAL}, {@code FROZEN}, {@code PII_REJECTED} (the
     *             message names the line index only), {@code NOT_CONFIRMED}, {@code BAD_SELECTION}
     */
    @Schema(name = "EvidenceShareError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull CaseErrorKind kind, @NonNull String code) implements EvidenceShareDTO, ErrorResponse {
    }
}
