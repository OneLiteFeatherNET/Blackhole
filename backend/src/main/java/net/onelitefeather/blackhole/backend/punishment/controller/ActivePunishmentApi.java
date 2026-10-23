package net.onelitefeather.blackhole.backend.punishment.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentSort;
import net.onelitefeather.blackhole.backend.punishment.SortDirection;
import net.onelitefeather.blackhole.backend.punishment.dto.ActivePunishmentDTO;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.util.UUID;

/** The overview of currently punished players. */
public interface ActivePunishmentApi {

    @Operation(
            summary = "List active punishments across players",
            description = "Lists every punishment that currently holds a player's active slot and has not ended, newest first by "
                    + "default. Every filter is optional. kind is MUTE, BAN or SERVER; section is a section key (a CloudNet target "
                    + "section is a SERVER section whose targetKind is CLOUDNET_TASK, CLOUDNET_GROUP or CLOUDNET_SERVICE); permanent "
                    + "selects permanent or temporary punishments; expiresBefore (epoch milliseconds) keeps temporary punishments "
                    + "that end before it; automatic selects those the ELO system applied; actor is the staff member's oid. "
                    + "sort is CREATED or EXPIRES (a permanent punishment sorts as the latest), direction ASC or DESC. Players "
                    + "appear as their hashed owner only; no raw UUID, IP address or ban ID is returned.",
            operationId = "listActivePunishments",
            tags = {"Punishment"}
    )
    @ApiResponse(responseCode = "200", description = "One page of active punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivePunishmentDTO.ListPage.class)))
    @ApiResponse(responseCode = "400", description = "A filter or the page is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivePunishmentDTO.Error.class)))
    @Get("/")
    HttpResponse<ActivePunishmentDTO> list(
            @Nullable @QueryValue EnforcementKind kind,
            @Nullable @QueryValue String section,
            @Nullable @QueryValue ServerTargetKind targetKind,
            @Nullable @QueryValue Boolean permanent,
            @Nullable @QueryValue Long expiresBefore,
            @Nullable @QueryValue Boolean automatic,
            @Nullable @QueryValue UUID actor,
            @Nullable @QueryValue ActivePunishmentSort sort,
            @Nullable @QueryValue SortDirection direction,
            @Nullable @QueryValue Integer page,
            @Nullable @QueryValue Integer size
    );
}
