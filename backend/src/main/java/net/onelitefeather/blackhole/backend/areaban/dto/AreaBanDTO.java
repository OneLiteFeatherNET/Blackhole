package net.onelitefeather.blackhole.backend.areaban.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/** Wire contract of the area ban routes: an ASN or a country, banned for everyone who connects from it. */
@Schema(description = "An area ban on an autonomous system or a country")
@Serdeable
public sealed interface AreaBanDTO {

    /**
     * @param kind            ASN or COUNTRY
     * @param value           the ASN number (with or without AS) or the ISO 3166 alpha-2 country code
     * @param reason          1 to 200 characters
     * @param durationSeconds the length of the ban, or null for a permanent ban
     */
    @Schema(name = "AreaBanCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(@NonNull String kind, @NonNull String value, @NonNull String reason,
                         @Nullable Long durationSeconds) implements AreaBanDTO {
    }

    /** @param reason why the ban is lifted, 1 to 200 characters */
    @Schema(name = "AreaBanLiftRequest")
    @Serdeable
    @ReflectiveAccess
    record LiftRequest(@NonNull String reason) implements AreaBanDTO {
    }

    @Schema(name = "AreaBanResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull String id, @NonNull String kind, @NonNull String value, @NonNull String reason,
                    @NonNull String source, long createdAt, @Nullable Long expiresAt, @Nullable Long liftedAt,
                    boolean active) implements AreaBanDTO {
    }

    /** @param bans the area bans that apply now */
    @Schema(name = "AreaBanListResponse")
    @Serdeable
    @ReflectiveAccess
    record ListResponse(@NonNull java.util.List<Response> bans) implements AreaBanDTO {
    }

    @Schema(name = "AreaBanError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements AreaBanDTO, ErrorResponse {
    }
}
