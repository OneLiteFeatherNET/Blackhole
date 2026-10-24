package net.onelitefeather.blackhole.backend.publicapi.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of every public error: a code and, for {@code NOT_ELIGIBLE}, the date filing opens.
 *
 * @param availableFrom an ISO instant, absent unless the player only has to wait
 */
@Schema(name = "PublicError", description = "A code the portal maps to a message")
@Serdeable
@ReflectiveAccess
public record PublicErrorBody(@NonNull String code, @Nullable String availableFrom) {

    public static PublicErrorBody of(PublicError error) {
        return new PublicErrorBody(error.code(), error.availableFrom());
    }
}
