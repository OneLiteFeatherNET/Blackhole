package net.onelitefeather.blackhole.backend.intel.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.intel.dto.SessionDTO;

public interface IntelApi {

    @Operation(
            summary = "Record a login attempt",
            description = "The proxy reports a login after its ban check, with the outcome. The session id is the proxy's "
                    + "random UUID for the connection: a repeat returns the stored record and writes nothing. The raw IP and "
                    + "player UUID are stored; the IP is enriched offline with its ASN and country. Proxy tokens only.",
            operationId = "recordPlayerSession",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "Stored (or already stored)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "A field is malformed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "blackhole.intel.enabled is false")
    @Post("/sessions")
    HttpResponse<SessionDTO> start(@Valid @Body SessionDTO.StartRequest request);

    @Operation(
            summary = "Update a session: client brand and version, or the disconnect",
            description = "The first end time is kept; a repeat disconnect changes nothing. Proxy tokens only.",
            operationId = "updatePlayerSession",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "A field is malformed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "No such session, or blackhole.intel.enabled is false")
    @Patch("/sessions/{sessionId}")
    HttpResponse<SessionDTO> update(@PathVariable String sessionId, @Valid @Body SessionDTO.UpdateRequest request);

    @Operation(
            summary = "List the sessions of a player",
            description = "The newest sessions of one owner, 50 per page by default. Shows the raw IP and the player UUID, so "
                    + "only staff may read it; a proxy may not.",
            operationId = "listPlayerSessions",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "One page of sessions",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionDTO.Page.class)))
    @ApiResponse(responseCode = "400", description = "The owner or the page parameters are invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionDTO.Error.class)))
    @Get("/players/{owner}/sessions")
    HttpResponse<SessionDTO> sessions(@PathVariable String owner, @QueryValue(defaultValue = "0") int page,
                                      @QueryValue(defaultValue = "50") int size);
}
