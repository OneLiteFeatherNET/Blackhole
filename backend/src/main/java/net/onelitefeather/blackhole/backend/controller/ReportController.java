package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.entities.ReportEntity;
import net.onelitefeather.blackhole.backend.database.repository.ReportRepository;
import net.onelitefeather.blackhole.backend.dto.EloReasonCode;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import net.onelitefeather.blackhole.backend.dto.ReportDTO;
import net.onelitefeather.blackhole.backend.dto.ReportRequestDTO;
import net.onelitefeather.blackhole.backend.dto.ReportResolutionDTO;
import net.onelitefeather.blackhole.backend.dto.ReportStatus;
import net.onelitefeather.blackhole.backend.elo.EloService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentApplicationService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Player reports. Submission is rate-limited per {@code reporterHash} - otherwise reporting
 * itself becomes a griefing vector. Resolution can optionally apply a punishment template to the
 * reported player through {@link PunishmentApplicationService}, the report system's actual
 * integration point with punishments rather than a parallel/separate mechanism.
 *
 * <p><b>Security note:</b> {@code reporterHash} is client-supplied - there is no authentication in
 * this system at all, so nothing here can verify it actually belongs to the caller. A
 * per-reporterHash limit alone is therefore bypassable by simply varying that field on every
 * request; {@link #rateLimitMaxReportsNetworkWide} is an aggregate, network-wide backstop that
 * caps the blast radius regardless of what {@code reporterHash} value is claimed.</p>
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@code resolve}'s {@code resolvedBy}
 * and {@code punishmentSource} are likewise client-supplied and unverified against the caller's
 * actual identity - the same property {@code PunishmentEntityController}'s {@code source} field
 * has had since Phase 0. This is a systemic actor-identity gap, not something specific to
 * reports, and is intentionally deferred to a dedicated pass that adds real per-actor identity
 * (and, if reintroduced, authentication) everywhere at once rather than patching one endpoint
 * inconsistently with the rest.</p>
 */
@Controller(ApiVersion.V1 + "/report")
public class ReportController {

    private final ReportRepository reportRepository;
    private final DomainEventPublisher eventPublisher;
    private final PunishmentApplicationService punishmentApplicationService;
    private final EloService eloService;
    private final int rateLimitMaxReports;
    private final int rateLimitMaxReportsNetworkWide;
    private final Duration rateLimitWindow;
    private final int reportActionedDelta;
    private final int reportRewardDelta;

    @Inject
    public ReportController(
            ReportRepository reportRepository,
            DomainEventPublisher eventPublisher,
            PunishmentApplicationService punishmentApplicationService,
            EloService eloService,
            @Value("${blackhole.report.rate-limit.max-reports:5}") int rateLimitMaxReports,
            @Value("${blackhole.report.rate-limit.max-reports-network-wide:50}") int rateLimitMaxReportsNetworkWide,
            @Value("${blackhole.report.rate-limit.window:PT10M}") Duration rateLimitWindow,
            @Value("${blackhole.elo.report.actioned-delta:-100}") int reportActionedDelta,
            @Value("${blackhole.elo.report.reward-delta:50}") int reportRewardDelta
    ) {
        this.reportRepository = reportRepository;
        this.eventPublisher = eventPublisher;
        this.punishmentApplicationService = punishmentApplicationService;
        this.eloService = eloService;
        this.rateLimitMaxReports = rateLimitMaxReports;
        this.rateLimitMaxReportsNetworkWide = rateLimitMaxReportsNetworkWide;
        this.rateLimitWindow = rateLimitWindow;
        this.reportActionedDelta = reportActionedDelta;
        this.reportRewardDelta = reportRewardDelta;
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
        long now = System.currentTimeMillis();
        long windowStart = now - this.rateLimitWindow.toMillis();

        long recentNetworkReports = this.reportRepository.countByCreatedAtGreaterThan(windowStart);
        if (recentNetworkReports >= this.rateLimitMaxReportsNetworkWide) {
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
        }

        long recentReports = this.reportRepository.countByReporterHashAndCreatedAtGreaterThan(
                submission.reporterHash(), windowStart
        );
        if (recentReports >= this.rateLimitMaxReports) {
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
        }

        ReportEntity report = new ReportEntity(
                submission.reporterHash(),
                submission.reportedHash(),
                submission.category(),
                submission.description(),
                submission.evidenceReferences() == null ? new ArrayList<>() : submission.evidenceReferences(),
                ReportStatus.OPEN,
                now,
                now,
                null,
                null,
                submission.metaData() == null ? new HashMap<>() : submission.metaData()
        );
        ReportEntity saved = this.reportRepository.save(report);

        this.eventPublisher.publish("report.created", Map.of(
                "reportIdentifier", saved.getIdentifier().toString(),
                "reporterHash", submission.reporterHash(),
                "reportedHash", submission.reportedHash(),
                "category", submission.category().toString()
        ));

        return HttpResponse.ok(saved.toDTO());
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
        Page<ReportEntity> entities = this.reportRepository.findAll(pageable);
        return HttpResponse.ok(entities.map(ReportEntity::toDTO));
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
        ReportEntity report = this.reportRepository.findById(identifier).orElse(null);
        if (report == null) {
            return HttpResponse.notFound();
        }

        if (resolution.punishmentTemplateId() != null) {
            if (resolution.punishmentSource() == null) {
                return HttpResponse.badRequest("punishmentSource is required when punishmentTemplateId is set");
            }
            var applied = this.punishmentApplicationService.apply(
                    report.getReportedHash(), resolution.punishmentTemplateId(), resolution.punishmentSource()
            );
            if (applied.isEmpty()) {
                return HttpResponse.notFound();
            }
        }

        report.setStatus(resolution.status());
        report.setResolutionNote(resolution.resolutionNote());
        report.setResolvedBy(resolution.resolvedBy());
        report.setUpdatedAt(System.currentTimeMillis());
        ReportEntity saved = this.reportRepository.update(report);

        if (resolution.status() == ReportStatus.ACTIONED) {
            EloTrack track = switch (report.getCategory()) {
                case CHAT_ABUSE -> EloTrack.CHAT;
                case CHEATING, GRIEFING -> EloTrack.GAMEPLAY;
                case OTHER -> null;
            };
            if (track != null) {
                this.eloService.applyDelta(
                        report.getReportedHash(), track, this.reportActionedDelta, EloReasonCode.REPORT_ACTIONED, null,
                        Map.of("reportIdentifier", identifier.toString(), "category", report.getCategory().toString())
                );

                // resolution.punishmentTemplateId() != null implies a punishment was actually
                // applied above (an empty apply() result returns 404 before this point is
                // reached) - a report only earns its reporter Elo when it demonstrably banned
                // someone, not merely when a staff member marked it ACTIONED without acting.
                if (resolution.punishmentTemplateId() != null) {
                    this.eloService.applyDelta(
                            report.getReporterHash(), track, this.reportRewardDelta, EloReasonCode.REPORT_REWARDED, null,
                            Map.of("reportIdentifier", identifier.toString(), "category", report.getCategory().toString())
                    );
                }
            }
        }

        this.eventPublisher.publish("report.resolved", Map.of(
                "reportIdentifier", identifier.toString(),
                "reportedHash", report.getReportedHash(),
                "status", resolution.status().toString(),
                "resolvedBy", resolution.resolvedBy().toString()
        ));

        return HttpResponse.ok(saved.toDTO());
    }
}
