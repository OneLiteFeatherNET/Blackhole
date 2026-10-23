package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordNotificationDTO;

import java.util.UUID;

public interface DiscordNotificationApi {

    @Operation(
            summary = "Facts for a report notification",
            description = "Called by the Discord bot only, after a report.created or report.resolved event. Returns the "
                    + "reported player's name when one was recorded (otherwise a short pseudonymous reference), the "
                    + "category, a shortened description and the status. Never a raw UUID.",
            operationId = "getReportNotification",
            tags = {"Discord notifications"}
    )
    @ApiResponse(responseCode = "200", description = "The report facts",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordNotificationDTO.Report.class)))
    @ApiResponse(responseCode = "404", description = "There is no such report",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordNotificationDTO.Error.class)))
    @Get("/report/{identifier}")
    HttpResponse<DiscordNotificationDTO> report(@PathVariable UUID identifier);

    @Operation(
            summary = "Facts for an appeal notification",
            description = "Called by the Discord bot only, after an appeal.submitted or appeal.resolved event. Returns the "
                    + "appellant's name when one was recorded (otherwise a short pseudonymous reference), the section, a "
                    + "shortened statement, the status and the punishment identifier for the case link. Never a Ban ID.",
            operationId = "getAppealNotification",
            tags = {"Discord notifications"}
    )
    @ApiResponse(responseCode = "200", description = "The appeal facts",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordNotificationDTO.Appeal.class)))
    @ApiResponse(responseCode = "404", description = "There is no such appeal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordNotificationDTO.Error.class)))
    @Get("/appeal/{identifier}")
    HttpResponse<DiscordNotificationDTO> appeal(@PathVariable UUID identifier);
}
