package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.UUID;

/** The four route groups of the Discord account link, kept together so the contract reads as one. */
public interface DiscordLinkApi {

    /** {@code /staff-discord-link}: a signed-in staff member links their own Discord account. */
    interface Staff {

        @Operation(
                summary = "Issue a Discord link code",
                description = "Issues the signed-in staff member a short-lived, single-use code (8 characters, shown as "
                        + "ABCD-EFGH) to type as /link in Discord. A new code replaces the caller's previous one. Only a "
                        + "signed-in person can link; a service principal gets 403. The code is returned only here and "
                        + "stored only as a hash.",
                operationId = "issueStaffDiscordLinkCode",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The code and when it expires",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.CodeResponse.class)))
        @ApiResponse(responseCode = "403", description = "The caller is not a signed-in person",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @Post("/code")
        HttpResponse<DiscordLinkDTO> issueCode(@Parameter(hidden = true) Actor actor);

        @Operation(
                summary = "Own Discord link status",
                description = "Whether the caller has a linked Discord account. Never contains a code or a Discord id.",
                operationId = "getOwnStaffDiscordLink",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The link status",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.StatusResponse.class)))
        @Get("/me")
        HttpResponse<DiscordLinkDTO> status(@Parameter(hidden = true) Actor actor);

        @Operation(
                summary = "Unlink own Discord account",
                description = "Removes the caller's Discord link and with it the stored Discord user id.",
                operationId = "unlinkOwnStaffDiscordLink",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The link was removed",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.RemovedResponse.class)))
        @ApiResponse(responseCode = "404", description = "The caller has no link",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @Delete("/me")
        HttpResponse<DiscordLinkDTO> unlink(@Parameter(hidden = true) Actor actor);
    }

    /** {@code /player-discord-link}: the Velocity proxy asks for a code for a player who typed /blackhole discord. */
    interface Player {

        @Operation(
                summary = "Issue a Discord link code for a player",
                description = "Called by the Velocity proxy only. Issues a short-lived, single-use code for the player "
                        + "identified by the hashed owner, to type as /link in Discord. A new code replaces the player's "
                        + "previous one. The code is stored only as a hash.",
                operationId = "issuePlayerDiscordLinkCode",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The code and when it expires",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.CodeResponse.class)))
        @ApiResponse(responseCode = "400", description = "The owner is missing or not a sha-512 hash",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @Post("/code")
        HttpResponse<DiscordLinkDTO> issueCode(@Body DiscordLinkDTO.PlayerCodeRequest request, @Parameter(hidden = true) Actor actor);
    }

    /** {@code /discord/link}: the Discord bot confirms codes and manages the link of a Discord user. */
    interface Bot {

        @Operation(
                summary = "Confirm a Discord link code",
                description = "Called by the Discord bot only. Binds the Discord user (the one who typed /link) to the staff "
                        + "member or player the code was issued to, replacing any earlier link of either side. A wrong, "
                        + "expired or already used code is answered identically with INVALID_CODE.",
                operationId = "confirmDiscordLink",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The link now exists",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.LinkedResponse.class)))
        @ApiResponse(responseCode = "400", description = "The Discord user id is malformed, or the code is invalid, expired or used (kind INVALID_CODE)",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @ApiResponse(responseCode = "409", description = "A concurrent confirmation took the account; try again",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @Post("/confirm")
        HttpResponse<DiscordLinkDTO> confirm(@Body DiscordLinkDTO.ConfirmRequest request);

        @Operation(
                summary = "What a Discord user is linked to",
                description = "Called by the Discord bot for /whoami: the kinds of link this Discord user has and, for a "
                        + "staff link, the staff member's name.",
                operationId = "lookupDiscordLink",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The links of the Discord user, possibly none",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.WhoamiResponse.class)))
        @Get("/{discordUserId}")
        HttpResponse<DiscordLinkDTO> whoami(@PathVariable String discordUserId);

        @Operation(
                summary = "Unlink a Discord user",
                description = "Called by the Discord bot for /unlink: removes every link of the Discord user and with it "
                        + "the stored Discord user id.",
                operationId = "unlinkDiscordUser",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The links were removed",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.RemovedResponse.class)))
        @ApiResponse(responseCode = "404", description = "The Discord user has no link",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @Delete("/{discordUserId}")
        HttpResponse<DiscordLinkDTO> unlink(@PathVariable String discordUserId);
    }

    /** {@code /admin/discord-link}: admins see and remove links. */
    interface Admin {

        @Operation(
                summary = "List Discord links",
                description = "Every staff and player link with the staff members' names. A Discord user id is never "
                        + "returned.",
                operationId = "listDiscordLinks",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The links, oldest first",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.ListResponse.class)))
        @Get
        HttpResponse<DiscordLinkDTO> list();

        @Operation(
                summary = "Remove a Discord link",
                description = "Removes one link and with it the stored Discord user id.",
                operationId = "removeDiscordLink",
                tags = {"Discord link"}
        )
        @ApiResponse(responseCode = "200", description = "The link was removed",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.RemovedResponse.class)))
        @ApiResponse(responseCode = "404", description = "There is no such link",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLinkDTO.Error.class)))
        @Delete("/{id}")
        HttpResponse<DiscordLinkDTO> remove(@PathVariable UUID id, @Parameter(hidden = true) Actor actor);
    }
}
