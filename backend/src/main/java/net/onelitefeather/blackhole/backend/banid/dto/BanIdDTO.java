package net.onelitefeather.blackhole.backend.banid.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/** Wire contract of {@code GET /punishment/{reference}/ban-id}. */
@Schema(description = "Revealing the Ban ID of a punishment to staff and the proxy")
@Serdeable
public sealed interface BanIdDTO {

    /**
     * @param banId the current Ban ID, eight Crockford base32 characters; a credential, never to be logged
     */
    @Schema(name = "BanIdResponse")
    @Serdeable
    record Response(@NonNull String banId) implements BanIdDTO {

        /** Never prints the Ban ID. */
        @Override
        public String toString() {
            return "BanIdDTO.Response[banId=***]";
        }
    }

    @Schema(name = "BanIdError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements BanIdDTO, ErrorResponse {
    }
}
