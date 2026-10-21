package net.onelitefeather.blackhole.backend.punishment.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;

public interface PunishmentApi {

    @Operation(
            summary = "Apply a punishment template",
            description = "Applies a template to a player: creates a temporary punishment in the template's section with "
                    + "the duration and ELO cost of the escalation step the player has reached, deducts the cost, and "
                    + "reports the step, the score before and after, and any permanent escalation it caused.",
            operationId = "applyPunishment",
            tags = {"Punishment"}
    )
    @ApiResponse(responseCode = "200", description = "Punishment applied",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.ApplyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (owner must be a SHA-512 hash, exactly one of templateKey and templateId)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown template",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The template's section is disabled, or the player is already permanently punished in it",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.Error.class)))
    @Post("/apply")
    HttpResponse<PunishmentDTO> apply(@Valid @Body PunishmentDTO.ApplyRequest request);

    @Operation(
            summary = "Revoke the active punishment of a section",
            description = "Moves the player's active punishment in the section into history. Revoking a permanent "
                    + "punishment resets the player's score in that section to the section's pardon score.",
            operationId = "revokePunishment",
            tags = {"Punishment"}
    )
    @ApiResponse(responseCode = "200", description = "Punishment revoked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.RevokeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (owner must be a SHA-512 hash)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "The player has no active punishment in that section",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.Error.class)))
    @Post("/revoke")
    HttpResponse<PunishmentDTO> revoke(@Valid @Body PunishmentDTO.RevokeRequest request);

    @Operation(
            summary = "Find punishments by short ID",
            description = "Finds the punishments whose short ID (the # and eight hex characters players are shown, e.g. "
                    + "#A1B2C3D4) matches. The # is optional and the case is ignored. Short IDs are four bytes and can "
                    + "collide, so the result is a list: empty if nothing matches, several if they collide.",
            operationId = "findPunishmentsByShortId",
            tags = {"Punishment"}
    )
    @ApiResponse(responseCode = "200", description = "The matching punishments, possibly none",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PunishEntryDTO.class))))
    @ApiResponse(responseCode = "400", description = "Not a short ID (exactly eight hex characters, optionally after #)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishmentDTO.Error.class)))
    @Get("/short/{shortId}")
    HttpResponse<?> findByShortId(@PathVariable String shortId);

    @Operation(
            summary = "Get all punishments",
            description = "Retrieves a paginated list of all punishment entries in the system",
            operationId = "getPunishments",
            tags = {"Punishment"}
    )
    @ApiResponse(responseCode = "200", description = "Successfully retrieved punishment entries",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PunishEntryDTO.class),
                            arraySchema = @Schema(implementation = Page.class))))
    @Get()
    HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable);
}
