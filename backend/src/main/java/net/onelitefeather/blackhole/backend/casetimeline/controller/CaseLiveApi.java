package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/** The staff live signal of one case: content-free, every staff-visible change, including the player's seen marker. */
public interface CaseLiveApi {

    @Operation(
            summary = "Live updates of a case",
            description = "A text/event-stream with the same frames as the player's stream: retry, ready {\"seq\"}, changed {\"seq\"} (id = seq) "
                    + "for every change of the case, including internal notes and the player's seen marker, a keep-alive every 15 seconds. The "
                    + "frames carry no content: after a changed event read GET /case/{reference} or the timeline with the cursor. Token refresh is "
                    + "the client's job: a stream is opened with a current token and ends after its maximum lifetime.",
            operationId = "caseEvents",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The event stream",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = "The token is missing or invalid")
    @ApiResponse(responseCode = "403", description = "The caller's role may not read cases")
    @ApiResponse(responseCode = "404", description = "Unknown punishment")
    @ApiResponse(responseCode = "503", description = "The replica holds its maximum of streams; poll instead")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @Get("/{reference}/events")
    HttpResponse<?> events(@PathVariable String reference, @Nullable @Header("Last-Event-ID") String lastEventId);
}
