package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.security.Actor;
import io.swagger.v3.oas.annotations.Parameter;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

public interface InboxApi {

    @Operation(
            summary = "Inbox",
            description = "What needs attention: appeal chats awaiting a team reply, open appeals, open reports and "
                    + "open cases without an assignee. Each section has its count and its oldest 10 items. changedByOthers lists the cases and appeals whose latest change in the last 24 hours came from another verified staff member.",
            operationId = "getInbox",
            tags = {"Inbox"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The four sections",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = InboxDTO.Response.class)
            )
    )
    @ApiResponse(
            responseCode = "503",
            description = "The inbox could not be read; retry",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = InboxDTO.Error.class)
            )
    )
    @Get
    HttpResponse<InboxDTO> summary(@Parameter(hidden = true) Actor actor);
}
