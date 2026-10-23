package net.onelitefeather.blackhole.backend.elo.controller;

import net.onelitefeather.blackhole.backend.security.Actor;
import io.swagger.v3.oas.annotations.Parameter;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;

public interface EloApi {

    String OWNER_HASH = "^[a-fA-F0-9]{128}$";

    @Operation(
            summary = "Score a chat message for toxicity",
            description = "Scores a chat message; if flagged, records evidence (a hash, never the raw text) and applies a "
                    + "delta in the configured chat section",
            operationId = "submitChatSignal",
            tags = {"Elo"}
    )
    @ApiResponse(responseCode = "200", description = "Scoring result",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloDTO.ChatSignalResponse.class)))
    @Post("/chat")
    HttpResponse<EloDTO.ChatSignalResponse> chat(@Valid @Body EloDTO.ChatSignalRequest signal);

    @Operation(
            summary = "Get a player's current ELO standing",
            description = "The player's score in every enabled section with that section's thresholds. A section the "
                    + "player was never scored in reports its base score.",
            operationId = "getEloProfile",
            tags = {"Elo"}
    )
    @ApiResponse(responseCode = "200", description = "Current ELO standing",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloDTO.ProfileResponse.class)))
    @Get("/{owner}")
    HttpResponse<EloDTO.ProfileResponse> getProfile(
            @PathVariable @Pattern(regexp = OWNER_HASH, message = "Owner must be a sha-512 hash") String owner
    );

    @Operation(
            summary = "Get a player's ELO history",
            description = "The audit trail of ELO changes for this owner, optionally restricted to one section",
            operationId = "getEloHistory",
            tags = {"Elo"}
    )
    @ApiResponse(responseCode = "200", description = "Paginated ELO event history",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = EloDTO.EventResponse.class),
                            arraySchema = @Schema(implementation = Page.class))))
    @Get("/{owner}/history")
    HttpResponse<Page<EloDTO.EventResponse>> getHistory(
            @PathVariable @Pattern(regexp = OWNER_HASH, message = "Owner must be a sha-512 hash") String owner,
            @QueryValue(defaultValue = "") String section,
            Pageable pageable
    );

    @Operation(
            summary = "Adjust a player's score by hand",
            description = "Applies a signed delta to the player's score in one section, recorded as a MANUAL_ADJUSTMENT "
                    + "and subject to the same soft and permanent threshold rules as any other delta. "
                    + "The adjusting staff member is the caller of the token.",
            operationId = "adjustElo",
            tags = {"Elo"}
    )
    @ApiResponse(responseCode = "200", description = "The score change and any automatic punishment it caused",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloDTO.AdjustResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid adjustment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown section",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The section is disabled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloDTO.Error.class)))
    @Post("/{owner}/adjust")
    HttpResponse<EloDTO> adjust(
            @PathVariable @Pattern(regexp = OWNER_HASH, message = "Owner must be a sha-512 hash") String owner,
            @Valid @Body EloDTO.AdjustRequest request,
            @Parameter(hidden = true) Actor actor
    );
}
