package net.onelitefeather.blackhole.backend.elo.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire contract of {@code /elo}: scoring a chat message, reading a player's per-section standing and
 * history, and adjusting a score by hand.
 */
@Schema(description = "ELO scores per punishment section")
@Serdeable
public sealed interface EloDTO {

    @Schema(name = "EloChatSignalRequest")
    @Serdeable
    @ReflectiveAccess
    record ChatSignalRequest(
            @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner,
            @NonNull @NotBlank @Size(max = 512) String message
    ) implements EloDTO {
    }

    @Schema(name = "EloChatSignalResponse")
    @Serdeable
    @ReflectiveAccess
    record ChatSignalResponse(boolean flagged, double score) implements EloDTO {
    }

    /**
     * One section's score of a player, with the thresholds that decide what happens when it drops.
     */
    @Schema(name = "EloSectionScore")
    @Serdeable
    @ReflectiveAccess
    record SectionScore(
            @NonNull String section,
            @NonNull String displayName,
            @NonNull EnforcementKind enforcement,
            int score,
            int baseScore,
            @Nullable Integer softThreshold,
            int permanentThreshold,
            int pardonScore
    ) {
    }

    @Schema(name = "EloProfileResponse")
    @Serdeable
    @ReflectiveAccess
    record ProfileResponse(@NonNull String owner, @NonNull List<SectionScore> sections) implements EloDTO {
    }

    @Schema(name = "EloEventResponse")
    @Serdeable
    @ReflectiveAccess
    record EventResponse(
            @NonNull UUID identifier,
            @NonNull String owner,
            @Nullable String section,
            int delta,
            @NonNull EloReasonCode reasonCode,
            @Nullable UUID sourceEvidenceId,
            int resultingScore,
            long createdAt,
            @NonNull Map<String, Object> metaData
    ) implements EloDTO {
    }

    @Schema(name = "EloAdjustRequest")
    @Serdeable
    @ReflectiveAccess
    record AdjustRequest(
            @NonNull @NotBlank String section,
            int delta,
            @Nullable @Size(max = 500) String note,
            @NonNull UUID source
    ) implements EloDTO {
    }

    /**
     * Result of an adjustment: the score change and the punishment the system applied because of it.
     */
    @Schema(name = "EloAdjustResponse")
    @Serdeable
    @ReflectiveAccess
    record AdjustResponse(
            @NonNull String section,
            int scoreBefore,
            int scoreAfter,
            @Nullable EscalationInfo escalation
    ) implements EloDTO {

        public static AdjustResponse of(EloChange.Applied applied) {
            return new AdjustResponse(
                    applied.section(), applied.scoreBefore(), applied.scoreAfter(),
                    applied.automatic().map(a -> new EscalationInfo(a.tier().name(), a.punishmentIdentifier())).orElse(null)
            );
        }
    }

    /**
     * An automatic punishment caused by a score change.
     *
     * @param tier                 {@code SOFT} (temporary) or {@code PERMANENT}
     * @param punishmentIdentifier the created punishment
     */
    @Schema(name = "EloEscalationInfo")
    @Serdeable
    @ReflectiveAccess
    record EscalationInfo(@NonNull String tier, @NonNull String punishmentIdentifier) {
    }

    @Schema(name = "EloError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements EloDTO, ErrorResponse {
    }
}
