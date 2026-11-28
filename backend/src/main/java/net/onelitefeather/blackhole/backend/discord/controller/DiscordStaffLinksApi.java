package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/** Routing and OpenAPI annotations. */
public interface DiscordStaffLinksApi {

    @Operation(
            summary = "Live staff links",
            description = "The Discord accounts of staff members with a live link, for the bot's channel access reconcile: only these accounts, "
                    + "when they also hold the staff role, may be in ticket channels.",
            operationId = "listDiscordStaffLinks",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The links",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.StaffLinksResponse.class)))
    @Get
    HttpResponse<DiscordLinkDTO> list();

    @Operation(
            summary = "Staff member of a Discord account",
            description = "The staff member linked to this Discord user, with whether a press would be allowed now. The bot asks once per press. "
                    + "The Discord id is in the path only and never in the answer.",
            operationId = "lookupDiscordStaffLink",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The linked staff member",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.StaffLookupResponse.class)))
    @ApiResponse(responseCode = "404", description = "No live staff link for this Discord user",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
    @Get("/{discordUserId}")
    HttpResponse<DiscordLinkDTO> lookup(@PathVariable String discordUserId);
}
