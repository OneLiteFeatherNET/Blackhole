package net.onelitefeather.blackhole.backend.report.controller;

import net.onelitefeather.blackhole.backend.report.dto.ReportDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportRequestDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import net.onelitefeather.blackhole.backend.report.service.ReportService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

import java.util.UUID;

/**
 * Player reports. Submission is rate-limited per {@code reporterHash} - otherwise reporting
 * itself becomes a griefing vector. Resolution can optionally apply a punishment template to the
 * reported player, the report system's actual integration point with punishments rather than a
 * parallel/separate mechanism. All of that logic lives in {@link ReportService}; this controller
 * only parses/validates the request, delegates, and maps the result to an {@code HttpResponse}.
 *
 * <p><b>Security note:</b> {@code reporterHash} is client-supplied - there is no authentication in
 * this system at all, so nothing here can verify it actually belongs to the caller. A
 * per-reporterHash limit alone is therefore bypassable by simply varying that field on every
 * request; the service's network-wide rate limit is an aggregate backstop that caps the blast
 * radius regardless of what {@code reporterHash} value is claimed.</p>
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@code resolve}'s {@code resolvedBy}
 * and {@code punishmentSource} are likewise client-supplied and unverified against the caller's
 * actual identity - the same property {@code PunishmentEntityController}'s {@code source} field
 * has had since Phase 0. This is a systemic actor-identity gap, not something specific to
 * reports, and is intentionally deferred to a dedicated pass that adds real per-actor identity
 * (and, if reintroduced, authentication) everywhere at once rather than patching one endpoint
 * inconsistently with the rest.</p>
 */
@Version(ApiVersion.V1)
@Controller("/report")
public class ReportController {

    private final ReportService reportService;

    @Inject
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

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
    public HttpResponse<?> submit(@Body @Valid ReportRequestDTO submission) {
        ReportService.SubmitResult result = this.reportService.submit(submission);
        return switch (result) {
            case ReportService.SubmitResult.Success success -> HttpResponse.ok(success.report());
            case ReportService.SubmitResult.RateLimited ignored -> HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
        };
    }

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
    public HttpResponse<Page<ReportDTO>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.reportService.getAll(pageable));
    }

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
    public HttpResponse<?> resolve(UUID identifier, @Body @Valid ReportResolutionDTO resolution) {
        ReportService.ResolveResult result = this.reportService.resolve(identifier, resolution);
        return switch (result) {
            case ReportService.ResolveResult.Success success -> HttpResponse.ok(success.report());
            case ReportService.ResolveResult.NotFound ignored -> HttpResponse.notFound();
            case ReportService.ResolveResult.PunishmentSourceRequired ignored ->
                    HttpResponse.badRequest("punishmentSource is required when punishmentTemplateId is set");
        };
    }
}
