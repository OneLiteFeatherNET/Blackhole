package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.discord.dto.StaffTicketDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

/** Routing and OpenAPI annotations of the staff lock of an appeal ticket. */
public interface StaffTicketApi {

    @Operation(
            summary = "Lock or unlock the ticket channel",
            description = "Sets (frozen true) or clears (frozen false) the staff lock of the appeal ticket of the case. A repeated value changes nothing "
                    + "and answers the current state. A freeze made by the leak check cannot be unlocked by staff (409 FREEZE_BY_LEAK). The Discord "
                    + "bot names the staff member with onBehalfOf.",
            operationId = "setStaffTicketFreeze",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The state afterwards",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffTicketDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "onBehalfOf from a caller that is not the Discord bot",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffTicketDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "The Discord bot named no staff member, or the staff member has no live link or no fresh moderator role "
            + "(STAFF_NOT_ELIGIBLE, STAFF_ROLE_STALE)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffTicketDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown case, or the case has no open ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "FREEZE_BY_LEAK: a leak freeze is not lifted by an unlock",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffTicketDTO.Error.class)))
    @Validated
    @Put("/{reference}/ticket/freeze")
    HttpResponse<StaffTicketDTO> freeze(@PathVariable String reference, @Body @Valid StaffTicketDTO.FreezeRequest request,
                                        @Parameter(hidden = true) Actor actor);
}
