package net.onelitefeather.blackhole.backend.report.service;

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
import net.onelitefeather.blackhole.backend.elo.EloTrack;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentApplicationService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the report lifecycle: rate-limited submission, and resolution (optionally applying a
 * punishment template to the reported player through {@link PunishmentApplicationService} and
 * feeding both ELO tracks' standing effects through {@link EloService}). Extracted from
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
 * <p><b>Security note:</b> {@code reporterHash} is client-supplied - there is no authentication in
 * this system at all, so nothing here can verify it actually belongs to the caller. A
 * per-reporterHash limit alone is therefore bypassable by simply varying that field on every
 * request; {@code rateLimitMaxReportsNetworkWide} is an aggregate, network-wide backstop that caps
 * the blast radius regardless of what {@code reporterHash} value is claimed.</p>
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@code resolve}'s {@code resolvedBy} and
 * {@code punishmentSource} are likewise client-supplied and unverified against the caller's actual
 * identity - the same property {@code PunishmentEntityController}'s {@code source} field has had
 * since Phase 0. This is a systemic actor-identity gap, not something specific to reports, and is
 * intentionally deferred to a dedicated pass that adds real per-actor identity (and, if
 * reintroduced, authentication) everywhere at once rather than patching one endpoint
 * inconsistently with the rest.</p>
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@code resolve} accepts any status and
 * can be re-invoked on the same report, re-triggering its standing ELO effects each time - there
 * is no status state machine enforcing valid transitions. Preserved as-is; introducing one is a
 * separate, more invasive behavioral change.</p>
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@code resolve}'s chain of side effects -
 * optional punishment application, the report's own status/resolutionNote/resolvedBy/updatedAt
 * update, up to two ELO deltas, and the final event publish - is not atomic. {@code @Transactional}
 * is unusable in this codebase (see {@link EloService}'s class Javadoc for why). A failure partway
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
    private final EloService eloService;
    private final int rateLimitMaxReports;
    private final int rateLimitMaxReportsNetworkWide;
    private final Duration rateLimitWindow;
    private final int reportActionedDelta;
    private final int reportRewardDelta;

    public ReportService(
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

        record NotFound() implements ResolveResult {
        }

        record PunishmentSourceRequired() implements ResolveResult {
        }
    }

    public Page<ReportDTO> getAll(Pageable pageable) {
        return this.reportRepository.findAll(pageable).map(ReportEntity::toDTO);
    }

    public SubmitResult submit(ReportRequestDTO submission) {
        long now = System.currentTimeMillis();
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

    public ResolveResult resolve(UUID identifier, ReportResolutionDTO resolution) {
        ReportEntity report = this.reportRepository.findById(identifier).orElse(null);
        if (report == null) {
            return new ResolveResult.NotFound();
        }

        if (resolution.punishmentTemplateId() != null) {
            if (resolution.punishmentSource() == null) {
                return new ResolveResult.PunishmentSourceRequired();
            }
            var applied = this.punishmentApplicationService.apply(
                    report.getReportedHash(), resolution.punishmentTemplateId(), resolution.punishmentSource()
            );
            if (applied.isEmpty()) {
                return new ResolveResult.NotFound();
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
                // applied above (an empty apply() result returns NotFound before this point is
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

        return new ResolveResult.Success(saved.toDTO());
    }
}
