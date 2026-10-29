package net.onelitefeather.blackhole.backend.exemption.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code /exemption}. Reads never expose a UUID or a name: the exemption is the source, the sections and the
 * staff rank only.
 */
@Schema(description = "Punishment exemption of players: linked staff and permission-exempt players")
@Serdeable
public sealed interface ExemptionDTO {

    /**
     * The exemption of one player.
     *
     * @param exempt  whether any exemption applies
     * @param sources {@code LINKED_STAFF} and/or {@code PERMISSION}
     * @param sections the sections covered by a permission ({@code *} for all); empty for a linked staff member only
     * @param rank    the rank the exemption protects at: {@code ADMIN}, {@code MODERATOR}; null when not exempt
     */
    @Schema(name = "ExemptionStatusResponse")
    @Serdeable
    @ReflectiveAccess
    record StatusResponse(boolean exempt, @NonNull List<String> sources, @NonNull List<String> sections, @Nullable String rank)
            implements ExemptionDTO {
    }

    /**
     * What the proxy reports for an online player: the exemption flags it read from the player's permissions.
     *
     * @param owner the hashed owner (SHA-512 hex, never the raw UUID)
     * @param flags {@code *} for {@code blackhole.exempt}, or a section key for {@code blackhole.exempt.<section>}; empty removes
     *              the permission exemption
     */
    @Schema(name = "ExemptionReportRequest")
    @Serdeable
    @ReflectiveAccess
    record ReportRequest(
            @NonNull @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner,
            @Nullable List<@Pattern(regexp = "^(\\*|[a-z0-9_-]{1,64})$", message = "a flag is * or a section key") String> flags
    ) implements ExemptionDTO {
    }

    /** An exemption review item of the admin queue. */
    @Schema(name = "ExemptionReview")
    @Serdeable
    @ReflectiveAccess
    record Review(@NonNull String id, @NonNull String owner, @NonNull String section, @NonNull String tier, int scoreAfter,
                  long crossedAt, @NonNull String state, @Nullable String justification) {
    }

    /** The open review items, oldest first. */
    @Schema(name = "ExemptionReviewListResponse")
    @Serdeable
    @ReflectiveAccess
    record ReviewListResponse(@NonNull List<Review> items) implements ExemptionDTO {
    }

    /** One review item after a decision. */
    @Schema(name = "ExemptionReviewResponse")
    @Serdeable
    @ReflectiveAccess
    record ReviewResponse(@NonNull Review review) implements ExemptionDTO {
    }

    /**
     * The justification an admin gives for a decision or an override.
     *
     * @param justification 1 to 500 characters, free text; checked for personal data before it is stored
     */
    @Schema(name = "ExemptionOverride")
    @Serdeable
    @ReflectiveAccess
    record Override(@NonNull @NotBlank @Size(min = 1, max = 500) String justification) {
    }

    /** The admin's answer on a review item: its justification. */
    @Schema(name = "ExemptionReviewDecisionRequest")
    @Serdeable
    @ReflectiveAccess
    record DecisionRequest(@Nullable @Size(max = 500) String justification) implements ExemptionDTO {
    }

    /** An expected failure. */
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ExemptionDTO, ErrorResponse {
    }
}
