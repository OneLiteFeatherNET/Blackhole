package net.onelitefeather.blackhole.backend.punishment.controller;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileResponse;

import java.util.UUID;

public interface PunishmentApi {

    @Operation(
            summary = "Add active punishment",
            description = "Creates and applies an active punishment to a player profile based on a punishment template",
            operationId = "addActivePunishment",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Punishment successfully applied to profile",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishProfileResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Profile or template not found"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters (owner must be SHA-512 hash)"
    )
    @Validated
    @Post("/active/{owner}/{templateId}/{source}")
    HttpResponse<PunishProfileResponse> add(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID templateId,
            UUID source
    );

    @Operation(
            summary = "Revoke active ban",
            description = "Revokes a player's active ban (SERVER or NETWORK), moving it into history",
            operationId = "revokeBan",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Ban successfully revoked",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishProfileResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found, or has no active ban"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters (owner must be SHA-512 hash)"
    )
    @Validated
    @Post("/active/{owner}/ban/revoke/{source}")
    HttpResponse<PunishProfileResponse> revokeBan(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID source
    );

    @Operation(
            summary = "Revoke active mute",
            description = "Revokes a player's active chat ban, moving it into history",
            operationId = "revokeMute",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Mute successfully revoked",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishProfileResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found, or has no active mute"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters (owner must be SHA-512 hash)"
    )
    @Validated
    @Post("/active/{owner}/mute/revoke/{source}")
    HttpResponse<PunishProfileResponse> revokeMute(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID source
    );

    @Operation(
            summary = "Get all punishments",
            description = "Retrieves a paginated list of all punishment entries in the system",
            operationId = "getPunishments",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved punishment entries",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(implementation = PunishEntryDTO.class),
                            arraySchema = @Schema(implementation = Page.class)
                    )
            )
    )
    @Get()
    HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable);
}
