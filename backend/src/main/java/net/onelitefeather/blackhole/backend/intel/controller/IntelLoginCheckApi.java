package net.onelitefeather.blackhole.backend.intel.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;

public interface IntelLoginCheckApi {

    @Operation(
            summary = "Verdict for one login: ALLOW, FLAG or DENY",
            description = "Called by the proxy after its ban check, with a short timeout. Classifies the address offline and "
                    + "applies the policy. A FLAG or an allow-listed match is recorded as a hit. Proxy tokens only.",
            operationId = "checkIntelLogin",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "The verdict",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginCheckDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The owner or the address is malformed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginCheckDTO.Error.class)))
    @Post("/login-check")
    HttpResponse<LoginCheckDTO> check(@Valid @Body LoginCheckDTO.CheckRequest request);
}
