package net.onelitefeather.blackhole.backend.intel.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of the VPN and hosting hits of a player ({@code /intel/players/{owner}/hits}). A hit holds the clear
 * address, so only staff read it.
 */
@Schema(description = "A login that matched a VPN, hosting or proxy list")
@Serdeable
public sealed interface HitDTO {

    /**
     * One stored hit.
     *
     * @param classification every matching class, comma-separated
     * @param sourceKey      the matching sources, comma-separated
     * @param verdict        the verdict the login got: ALLOW, FLAG or DENY
     */
    @Schema(name = "HitResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull String id, @NonNull String ip, @NonNull String classification, @NonNull String sourceKey,
                    @Nullable Long asn, @Nullable String organisation, @NonNull IntelPolicy verdict, boolean allowListed,
                    long createdAt) implements HitDTO {
    }

    /**
     * The newest hits of one owner, newest first.
     */
    @Schema(name = "HitPage")
    @Serdeable
    @ReflectiveAccess
    record Page(@NonNull List<Response> hits) implements HitDTO {
    }

    @Schema(name = "HitError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements HitDTO, ErrorResponse {
    }
}
