package net.onelitefeather.blackhole.backend.report.service;

import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportRepository;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.report.dto.ReportDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportRequestDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import io.micronaut.context.annotation.Value;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.report.CategorySectionMapping;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentApplicationService;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the report lifecycle: rate-limited submission, and resolution (optionally applying a
 * punishment template to the reported player through {@link PunishmentApplicationService} and
 * feeding the per-section ELO effects through {@link EloScoring}). Extracted from
 * {@code ReportController}, which previously inlined all of this directly - the most severe
 * layering gap found across the retroactive spec-kit review of the 6 subsystems (see
 * {@code specs/006-player-reports/plan.md}'s Constitution Check).
 *
 * <p><b>Ordering matters for {@link #resolve}:</b> punishment-template lookup/application is
 * attempted first, and any failure there (template missing) is returned before a single field on
 * the {@link ReportEntity} is mutated - so a failed resolution never leaves the report
 * half-updated (FR-013/SC-006's "no partial completion" guarantee). Preserve this ordering in any
 * future change to this method.</p>
 *
 * <p><b>Security note:</b> the caller is the verified Entra ID token, but the reporter is not taken
 * from it. {@code reporterHash} is still client-supplied and cannot be verified, so a
 * per-reporterHash limit can be bypassed by varying it. {@code rateLimitMaxReportsNetworkWide} is
 * the aggregate, network-wide backstop that caps the blast radius.</p>
 *
 * <p>{@code resolve} takes the resolver from the verified {@link Actor}: {@code resolvedBy} and the
 * source of an applied punishment are the caller's {@code oid}, and {@code recorded_by_oid} is set
 * to the same token's {@code oid}. The request carries no actor.</p>
 *
 * <p>A report resolves once: resolving an already {@code ACTIONED} or {@code DISMISSED} report is
 * refused ({@link ResolveResult.AlreadyResolved}) so neither the template nor the ELO effects repeat.
 * With a template, the template's step cost is the only deduction from the reported player; the
 * flat {@code actioned-delta} applies to an {@code ACTIONED} report without a template. A template
 * is only accepted together with {@code ACTIONED}. The check-then-resolve is not atomic, so two
 * simultaneous resolutions of one open report can both pass it.</p>
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@code resolve}'s chain of side effects -
 * optional punishment application, the report's own status/resolutionNote/resolvedBy/updatedAt
 * update, up to two ELO deltas, and the final event publish - is not atomic. {@code @Transactional}
 * is unusable in this codebase (see {@code EloService}'s class Javadoc for why). A failure partway
 * through (e.g. the report {@code update()} throwing after
 * {@code punishmentApplicationService.apply()} already succeeded) can leave a punishment applied
 * against a report that never recorded its own resolution, or an ELO delta applied without the
 * report reflecting the status that triggered it. This is an accepted race window in the same
 * spirit as the one already documented for {@code EloService} and {@code AppealDecisionService},
 * not newly introduced here.</p>
 */
@Singleton
public class ReportService {

    private final ReportRepository reportRepository;
    private final DomainEventPublisher eventPublisher;
    private final PunishmentApplicationService punishmentApplicationService;
    private final EloScoring eloService;
    private final CategorySectionMapping categorySections;
    private final Clock clock;
    private final int rateLimitMaxReports;
    private final int rateLimitMaxReportsNetworkWide;
    private final Duration rateLimitWindow;
    private final int reportActionedDelta;
    private final int reportRewardDelta;

    public ReportService(
            ReportRepository reportRepository,
            DomainEventPublisher eventPublisher,
            PunishmentApplicationService punishmentApplicationService,
            EloScoring eloService,
            CategorySectionMapping categorySections,
            Clock clock,
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
        this.categorySections = categorySections;
        this.clock = clock;
        this.rateLimitMaxReports = rateLimitMaxReports;
        this.rateLimitMaxReportsNetworkWide = rateLimitMaxReportsNetworkWide;
        this.rateLimitWindow = rateLimitWindow;
        this.reportActionedDelta = reportActionedDelta;
        this.reportRewardDelta = reportRewardDelta;
    }

    /**
     * Outcome of {@link #submit}. An expected failure (rate limit) is a variant here rather than
     * an exception, so the controller maps it to an {@code HttpResponse} without try/catch.
     */
    public sealed interface SubmitResult {
        record Success(ReportDTO report) implements SubmitResult {
        }

        record RateLimited() implements SubmitResult {
        }
    }

    /**
     * Outcome of {@link #resolve}. Expected failures are variants here rather than exceptions, so
     * the controller maps them to an {@code HttpResponse} without try/catch.
     */
    public sealed interface ResolveResult {
        record Success(ReportDTO report) implements ResolveResult {
        }

        /** There is no report with the given identifier. */
        record NotFound() implements ResolveResult {
        }

        /** The report was already resolved (actioned or dismissed); nothing is applied a second time. */
        record AlreadyResolved(ReportStatus status) implements ResolveResult {
        }

        /** A punishment template was given for a resolution that is not {@code ACTIONED}. */
        record TemplateNotAllowed(String message) implements ResolveResult {
        }

        /**
         * The punishment could not be applied: an unknown template or section (a problem of the
         * request, {@code VALIDATION}), a disabled section or an already permanently punished player
         * ({@code CONFLICT}).
         */
        record PunishmentRejected(ErrorKind kind, String message) implements ResolveResult {
        }
    }

    public Page<ReportDTO> getAll(Pageable pageable) {
        return this.reportRepository.findAll(pageable).map(ReportEntity::toDTO);
    }

    public SubmitResult submit(ReportRequestDTO submission) {
        long now = this.clock.millis();
        long windowStart = now - this.rateLimitWindow.toMillis();

        long recentNetworkReports = this.reportRepository.countByCreatedAtGreaterThan(windowStart);
        if (recentNetworkReports >= this.rateLimitMaxReportsNetworkWide) {
            return new SubmitResult.RateLimited();
        }

        long recentReports = this.reportRepository.countByReporterHashAndCreatedAtGreaterThan(
                submission.reporterHash(), windowStart
        );
        if (recentReports >= this.rateLimitMaxReports) {
            return new SubmitResult.RateLimited();
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

        return new SubmitResult.Success(saved.toDTO());
    }

    public ResolveResult resolve(UUID identifier, ReportResolutionDTO resolution, Actor actor) {
        ReportEntity report = this.reportRepository.findById(identifier).orElse(null);
        if (report == null) {
            return new ResolveResult.NotFound();
        }

        if (report.getStatus() == ReportStatus.ACTIONED || report.getStatus() == ReportStatus.DISMISSED) {
            return new ResolveResult.AlreadyResolved(report.getStatus());
        }

        if (resolution.punishmentTemplateId() != null) {
            if (resolution.status() != ReportStatus.ACTIONED) {
                return new ResolveResult.TemplateNotAllowed("a punishment template can only be applied when the report is ACTIONED");
            }
            PunishmentDTO applied = this.punishmentApplicationService.apply(new PunishmentDTO.ApplyRequest(
                    report.getReportedHash(), null, resolution.punishmentTemplateId(), null
            ), actor);
            if (applied instanceof PunishmentDTO.Error error) {
                // A missing template or section is reported as a problem of the request, so it cannot be
                // mistaken for "report not found".
                ErrorKind kind = error.kind() == ErrorKind.NOT_FOUND ? ErrorKind.VALIDATION : error.kind();
                return new ResolveResult.PunishmentRejected(kind, error.errorMessage());
            }
        }

        report.setStatus(resolution.status());
        report.setResolutionNote(resolution.resolutionNote());
        report.setResolvedBy(actor.oid());
        report.setRecordedByOid(actor.oid());
        report.setUpdatedAt(this.clock.millis());
        ReportEntity saved = this.reportRepository.update(report);

        if (resolution.status() == ReportStatus.ACTIONED) {
            String section = this.categorySections.sectionFor(report.getCategory()).orElse(null);
            if (section != null) {
                Map<String, Object> eloMetaData = Map.of("reportIdentifier", identifier.toString(), "category", report.getCategory().toString());
                // With a template the step's own cost (taken when the template was applied above) is the
                // deduction; the flat actioned delta is only for an ACTIONED report without a template.
                if (resolution.punishmentTemplateId() == null) {
                    this.eloService.applyDelta(
                            report.getReportedHash(), section, this.reportActionedDelta, EloReasonCode.REPORT_ACTIONED, null, eloMetaData
                    );
                }

                // resolution.punishmentTemplateId() != null implies a punishment was actually
                // applied above (an empty apply() result returns NotFound before this point is
                // reached) - a report only earns its reporter Elo when it demonstrably banned
                // someone, not merely when a staff member marked it ACTIONED without acting.
                if (resolution.punishmentTemplateId() != null) {
                    this.eloService.applyDelta(
                            report.getReporterHash(), section, this.reportRewardDelta, EloReasonCode.REPORT_REWARDED, null, eloMetaData
                    );
                }
            }
        }

        this.eventPublisher.publish("report.resolved", Map.of(
                "reportIdentifier", identifier.toString(),
                "reportedHash", report.getReportedHash(),
                "status", resolution.status().toString(),
                "resolvedBy", actor.oid().toString(),
                "recordedByOid", actor.oid().toString()
        ));

        return new ResolveResult.Success(saved.toDTO());
    }
}
