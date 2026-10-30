package net.onelitefeather.blackhole.backend.guard.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/**
 * Wire contract of the join guard settings routes. The settings are one singleton row, so the request replaces the whole
 * object and carries no identifier.
 */
@Schema(description = "The editable unattended settings of the join guard")
@Serdeable
public sealed interface GuardSettingsDTO {

    /**
     * @param unattendedLevel       STRICT or LOCKDOWN
     * @param absenceThresholdMinutes whole minutes, 5 to 240
     * @param nightWindowEnabled    whether the night window applies
     * @param nightWindowStart      HH:mm, inclusive
     * @param nightWindowEnd        HH:mm, exclusive; may be earlier than the start
     * @param timeZone              an IANA region id such as Europe/Berlin
     */
    @Schema(name = "GuardSettingsRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(@Nullable String unattendedLevel, @Nullable Integer absenceThresholdMinutes,
                         @Nullable Boolean nightWindowEnabled, @Nullable String nightWindowStart,
                         @Nullable String nightWindowEnd, @Nullable String timeZone) implements GuardSettingsDTO {
    }

    /**
     * @param updatedAt             when the settings were last saved, epoch milliseconds
     * @param updatedByOid          the administrator who saved them; null while they are the seeded configuration
     * @param nextNightWindowStart  start of the next night window in epoch milliseconds, or null when the window is disabled
     * @param nextNightWindowEnd    end of that window in epoch milliseconds, or null when the window is disabled
     */
    @Schema(name = "GuardSettingsResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull String unattendedLevel, int absenceThresholdMinutes, boolean nightWindowEnabled,
                    @NonNull String nightWindowStart, @NonNull String nightWindowEnd, @NonNull String timeZone, long updatedAt,
                    @Nullable String updatedByOid, @Nullable Long nextNightWindowStart,
                    @Nullable Long nextNightWindowEnd) implements GuardSettingsDTO {
    }

    @Schema(name = "GuardSettingsError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements GuardSettingsDTO, ErrorResponse {
    }
}
