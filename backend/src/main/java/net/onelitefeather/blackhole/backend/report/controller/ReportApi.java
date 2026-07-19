package net.onelitefeather.blackhole.backend.report.controller;

import net.onelitefeather.blackhole.backend.report.dto.ReportDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportRequestDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
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
            description = "Retrieves a paginated list of reports",
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
    HttpResponse<Page<ReportDTO>> getAll(Pageable pageable);

    @Operation(
            summary = "Resolve a report",
            description = "Resolves a report, optionally applying a punishment template to the reported player",
            operationId = "resolveReport",
            tags = {"Report"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Report successfully resolved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Report or punishment template not found")
    @ApiResponse(responseCode = "400", description = "punishmentSource is required when punishmentTemplateId is set")
    @Validated
    @Post("/{identifier}/resolve")
    HttpResponse<?> resolve(UUID identifier, @Body @Valid ReportResolutionDTO resolution);
}
