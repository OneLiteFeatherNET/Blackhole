package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

@Serdeable
public interface PunishProfileResponse {

    /**
     * The error response when something went wrong on profile requests
     *
     * @param message the error message
     */
    @Schema(name = "ErrorResponse", description = "The error response when something went wrong on profile requests")
    @Serdeable
    record ErrorResponse(
            @Schema(name = "The error message") String message
    ) implements PunishProfileResponse {}
}
