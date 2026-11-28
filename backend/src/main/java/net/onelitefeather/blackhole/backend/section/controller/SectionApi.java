package net.onelitefeather.blackhole.backend.section.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.util.List;

public interface SectionApi {

    @Operation(
            summary = "Create a punishment section",
            description = "Creates an enabled section with its own ELO score, thresholds and enforcement kind. A SERVER section lists glob patterns (targetKind PATTERN, the default) or exact CloudNet task, group or service names.",
            operationId = "createSection",
            tags = {"Section"}
    )
    @ApiResponse(responseCode = "200", description = "Section created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid section definition",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "A section with this key already exists",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Error.class)))
    @Post("/")
    HttpResponse<SectionDTO> create(@Valid @Body SectionDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Update a punishment section",
            description = "Replaces every editable field of an existing section, including whether it is enabled.",
            operationId = "updateSection",
            tags = {"Section"}
    )
    @ApiResponse(responseCode = "200", description = "Section updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid section definition",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Section not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Error.class)))
    @Post("/update")
    HttpResponse<SectionDTO> update(@Valid @Body SectionDTO.UpdateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List punishment sections",
            description = "All sections, enabled or not, sorted by key.",
            operationId = "listSections",
            tags = {"Section"}
    )
    @ApiResponse(responseCode = "200", description = "The sections",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = SectionDTO.Response.class))))
    @Get("/")
    HttpResponse<List<SectionDTO.Response>> list();

    @Operation(
            summary = "Get a punishment section",
            operationId = "getSection",
            tags = {"Section"}
    )
    @ApiResponse(responseCode = "200", description = "The section",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Section not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Error.class)))
    @Get("/{key}")
    HttpResponse<SectionDTO> get(@PathVariable String key);

    @Operation(
            summary = "Disable a punishment section",
            description = "Rejects new template applications and score deltas for the section. Existing active "
                    + "punishments stay enforced until they expire or are lifted.",
            operationId = "disableSection",
            tags = {"Section"}
    )
    @ApiResponse(responseCode = "200", description = "Section disabled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Section not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SectionDTO.Error.class)))
    @Post("/{key}/disable")
    HttpResponse<SectionDTO> disable(@PathVariable String key, @Parameter(hidden = true) Actor actor);
}
