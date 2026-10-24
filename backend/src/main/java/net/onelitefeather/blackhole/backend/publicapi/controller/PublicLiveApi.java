package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Produces;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

/** The live signal of the player's conversation: a content-free stream. */
public interface PublicLiveApi {

    @Operation(
            summary = "Live updates of the conversation",
            description = "A text/event-stream: retry (once), ready {\"seq\"} on connect, changed {\"seq\"} (id = seq) after every change the player "
                    + "may read, expired {} and then the end when the session ends, replaced {} when another stream of the same session opens, "
                    + "and a keep-alive comment every 15 seconds. No text, name or file name is ever sent: after a changed event the client reads "
                    + "GET /public/conversation with its cursor. Last-Event-ID below the current seq gets a changed right after ready. One stream "
                    + "per session; the stream ends after its maximum lifetime and the client reconnects.",
            operationId = "publicConversationEvents",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The event stream",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "503", description = "UNAVAILABLE: the replica holds its maximum of streams; poll instead",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @Get("/conversation/events")
    HttpResponse<?> events(@Parameter(hidden = true) PlayerSession session, @Nullable @Header("Last-Event-ID") String lastEventId);
}
