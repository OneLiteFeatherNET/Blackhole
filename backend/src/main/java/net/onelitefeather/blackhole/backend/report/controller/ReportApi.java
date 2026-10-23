package net.onelitefeather.blackhole.backend.report.controller;

import net.onelitefeather.blackhole.backend.security.Actor;
import io.swagger.v3.oas.annotations.Parameter;
import net.onelitefeather.blackhole.backend.report.dto.ReportDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportErrorDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportRequestDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

import java.util.UUID;

public interface ReportApi {

    @Operation(
            summary = "Submit a report",
            description = "Submits a player report. Rate-limited per reporterHash within a configurable time window.",
            operationId = "submitReport",
            tags = {"Report"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Report successfully submitted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportDTO.class))
    )
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for this reporter")
    @Validated
    @Post("/")
    HttpResponse<?> submit(@Body @Valid ReportRequestDTO submission);

    @Operation(
            summary = "Get all reports",
            description = "Retrieves a paginated list of reports. With reportedHash only the reports against that "
                    + "player are listed, newest first.",
            operationId = "getReports",
            tags = {"Report"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved reports",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ReportDTO.class), arraySchema = @Schema(implementation = Page.class))
            )
    )
    @Get("/")
    HttpResponse<Page<ReportDTO>> getAll(Pageable pageable, @Parameter(description = "Only the reports against this player (owner hash)", required = false) @Nullable @QueryValue String reportedHash);

    @Operation(
            summary = "Get a report",
            description = "Retrieves one report by its identifier.",
            operationId = "getReport",
            tags = {"Report"}
    )
    @ApiResponse(
            responseCode = "200", description = "The report",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportDTO.class))
    )
    @ApiResponse(
            responseCode = "404", description = "There is no report with this identifier",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportErrorDTO.class))
    )
    @Get("/{identifier}")
    HttpResponse<?> get(UUID identifier);

    @Operation(
            summary = "Resolve a report",
            description = "Resolves a report, optionally applying a punishment template to the reported player. "
                    + "The resolver and the punishment source are the caller of the token.",
            operationId = "resolveReport",
            tags = {"Report"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Report successfully resolved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportDTO.class))
    )
    @ApiResponse(
            responseCode = "404", description = "There is no report with this identifier",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportErrorDTO.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "A template was given for a report that is not ACTIONED, "
                    + "or the template or its section does not exist",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportErrorDTO.class))
    )
    @ApiResponse(
            responseCode = "409",
            description = "The report is already resolved, the template's section is disabled, or the player is already permanently punished there",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportErrorDTO.class))
    )
    @Validated
    @Post("/{identifier}/resolve")
    HttpResponse<?> resolve(UUID identifier, @Body @Valid ReportResolutionDTO resolution, @Parameter(hidden = true) Actor actor);
}
