package net.onelitefeather.blackhole.backend.section.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionSpec;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.util.List;

/**
 * Wire contract of {@code /section}. A section is identified by its client-chosen kebab-case key,
 * so unlike a server-assigned identifier the key is present on create as well.
 */
@Schema(description = "A punishment section: owns a player's ELO score, thresholds and enforcement")
@Serdeable
public sealed interface SectionDTO {

    @Schema(name = "SectionCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull @NotBlank String key,
            @NonNull @NotBlank String displayName,
            @NonNull EnforcementKind enforcement,
            @Nullable ServerTargetKind targetKind,
            @Nullable List<String> servers,
            @NonNull Integer baseScore,
            @Nullable Integer softThreshold,
            @Nullable String softDuration,
            @NonNull Integer permanentThreshold,
            @NonNull Integer pardonScore,
            @NonNull @NotBlank String permanentReason,
            @Nullable Integer decayPerDay
    ) implements SectionDTO {

        public static final int DEFAULT_DECAY_PER_DAY = 10;

        public SectionSpec toSpec() {
            return new SectionSpec(
                    this.key, this.displayName, this.enforcement, this.targetKind, this.servers, this.baseScore, this.softThreshold,
                    this.softDuration, this.permanentThreshold, this.pardonScore, this.permanentReason,
                    this.decayPerDay == null ? DEFAULT_DECAY_PER_DAY : this.decayPerDay
            );
        }
    }

    @Schema(name = "SectionUpdateRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(
            @NonNull @NotBlank String key,
            @NonNull @NotBlank String displayName,
            @NonNull EnforcementKind enforcement,
            @Nullable ServerTargetKind targetKind,
            @Nullable List<String> servers,
            @NonNull Integer baseScore,
            @Nullable Integer softThreshold,
            @Nullable String softDuration,
            @NonNull Integer permanentThreshold,
            @NonNull Integer pardonScore,
            @NonNull @NotBlank String permanentReason,
            @NonNull Integer decayPerDay,
            boolean enabled
    ) implements SectionDTO {

        public SectionSpec toSpec() {
            return new SectionSpec(
                    this.key, this.displayName, this.enforcement, this.targetKind, this.servers, this.baseScore, this.softThreshold,
                    this.softDuration, this.permanentThreshold, this.pardonScore, this.permanentReason, this.decayPerDay
            );
        }
    }

    @Schema(name = "SectionResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String key,
            @NonNull String displayName,
            @NonNull EnforcementKind enforcement,
            @NonNull ServerTargetKind targetKind,
            @NonNull List<String> servers,
            int baseScore,
            @Nullable Integer softThreshold,
            @Nullable String softDuration,
            int permanentThreshold,
            int pardonScore,
            @NonNull String permanentReason,
            int decayPerDay,
            boolean enabled
    ) implements SectionDTO {

        /** A cache entry written before target kinds existed has none: it is a pattern section. */
        public Response {
            targetKind = targetKind == null ? ServerTargetKind.PATTERN : targetKind;
        }
    }

    @Schema(name = "SectionError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements SectionDTO, ErrorResponse {
    }
}
