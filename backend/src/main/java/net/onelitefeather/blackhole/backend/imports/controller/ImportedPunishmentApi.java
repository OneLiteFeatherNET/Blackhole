package net.onelitefeather.blackhole.backend.imports.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.imports.dto.ImportedPunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface ImportedPunishmentApi {

    @Operation(
            summary = "List imported punishments without a template",
            description = "Groups the punishments imported from a vanilla ban list that are linked to no template by their "
                    + "original reason, the largest group first.",
            operationId = "listImportedPunishmentGroups",
            tags = {"Import"}
    )
    @ApiResponse(responseCode = "200", description = "The groups",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportedPunishmentDTO.Groups.class)))
    @Get("/")
    HttpResponse<ImportedPunishmentDTO> groups();

    @Operation(
            summary = "Link imported punishments to a template",
            description = "Links imported punishments that have no template to a template, either every one with an original "
                    + "reason or an explicit selection. Only the template changes: no score, step, duration or section. "
                    + "Each change is recorded on the case as punishment.retemplated with the caller as actor. A punishment "
                    + "that already has a template is never overwritten, so repeating a request changes nothing.",
            operationId = "retemplateImportedPunishments",
            tags = {"Import"}
    )
    @ApiResponse(responseCode = "200", description = "What was linked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportedPunishmentDTO.RetemplateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Neither or both of reason and identifiers, or the template is in a mute section",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportedPunishmentDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown template",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportedPunishmentDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The template's section is disabled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportedPunishmentDTO.Error.class)))
    @Post("/retemplate")
    HttpResponse<ImportedPunishmentDTO> retemplate(@Valid @Body ImportedPunishmentDTO.RetemplateRequest request, @Parameter(hidden = true) Actor actor);
}
