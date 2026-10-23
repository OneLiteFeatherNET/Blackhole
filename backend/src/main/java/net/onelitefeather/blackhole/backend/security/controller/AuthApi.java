package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.security.dto.AuthDTO;

public interface AuthApi {

    @Operation(
            summary = "Who am I",
            description = "The object id, display name and internal roles of the caller. Answers 200 with an empty "
                    + "role list when the token is valid but maps to no role, so a client can show a no-access page.",
            operationId = "getCurrentCaller",
            tags = {"Auth"}
    )
    @ApiResponse(responseCode = "200", description = "The caller",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthDTO.Response.class)))
    @ApiResponse(responseCode = "401", description = "No valid token, or the token has no object id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthDTO.Error.class)))
    @Get("/me")
    HttpResponse<AuthDTO> me(Authentication authentication);
}
