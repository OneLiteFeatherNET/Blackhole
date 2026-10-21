package net.onelitefeather.blackhole.backend.report.service;

import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.report.CategorySectionMapping;
import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportRepository;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEloScoring;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.onelitefeather.blackhole.backend.response.ErrorKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {

    private static final String REPORTED = "aa".repeat(64);
    private static final String REPORTER = "bb".repeat(64);

    private final RecordingEloScoring scoring = new RecordingEloScoring();
    private final InMemoryRepository<ReportRepository, ReportEntity, UUID> reports =
            InMemoryRepository.<ReportRepository, ReportEntity, UUID>of(ReportRepository.class, ReportEntity::getIdentifier).build();
    private final CategorySectionMapping mapping = new CategorySectionMapping(
            Map.of("CHAT_ABUSE", "chat", "CHEATING", "network", "GRIEFING", "network", "OTHER", ""));
    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final ReportService service = new ReportService(
            reports.repository(), new RecordingEventPublisher(), bed.punishmentService, scoring, mapping, bed.eloBed.clock, 5, 50, Duration.ofMinutes(10), -100, 50);

    private UUID givenReport(ReportCategory category) {
        UUID id = UUID.randomUUID();
        reports.given(new ReportEntity(REPORTER, REPORTED, category, "description", new ArrayList<>(), ReportStatus.OPEN,
                0L, 0L, null, null, new HashMap<>()), id);
        return id;
    }

    private ReportService.ResolveResult resolve(UUID id, ReportStatus status) {
        return service.resolve(id, new ReportResolutionDTO(status, "note", UUID.randomUUID(), null, null));
    }

    @Test
    void anActionedCheatingReportCostsTheReportedPlayerNetworkElo() {
        UUID id = givenReport(ReportCategory.CHEATING);

        assertInstanceOf(ReportService.ResolveResult.Success.class, resolve(id, ReportStatus.ACTIONED));

        assertEquals(1, scoring.deltas().size(), "one delta, no reward without a punishment");
        assertEquals(REPORTED, scoring.deltas().getFirst().owner());
        assertEquals("network", scoring.deltas().getFirst().section());
        assertEquals(-100, scoring.deltas().getFirst().delta());
        assertEquals(EloReasonCode.REPORT_ACTIONED, scoring.deltas().getFirst().reason());
    }

    @Test
    void anActionedChatAbuseReportScoresInTheChatSection() {
        UUID id = givenReport(ReportCategory.CHAT_ABUSE);

        resolve(id, ReportStatus.ACTIONED);

        assertEquals("chat", scoring.deltas().getFirst().section());
    }

    @Test
    void anActionedReportInACategoryWithoutASectionAppliesNoDelta() {
        UUID id = givenReport(ReportCategory.OTHER);

        assertInstanceOf(ReportService.ResolveResult.Success.class, resolve(id, ReportStatus.ACTIONED));

        assertTrue(scoring.deltas().isEmpty(), "category OTHER has no ELO effect");
    }

    private ReportService.ResolveResult resolveWithTemplate(UUID id, UUID templateId) {
        return service.resolve(id, new ReportResolutionDTO(ReportStatus.ACTIONED, "note", UUID.randomUUID(), templateId, UUID.randomUUID()));
    }

    @Test
    void anActionedReportWithAPunishmentAlsoRewardsTheReporterInTheSameSection() {
        UUID id = givenReport(ReportCategory.CHEATING);
        var template = bed.givenTemplate("grief", "network", null, PunishmentTestBed.step("P3D", 0));

        assertInstanceOf(ReportService.ResolveResult.Success.class, resolveWithTemplate(id, template.getIdentifier()));

        assertEquals(1, scoring.deltas().size(), "only the reporter is rewarded; the template's step cost is the penalty");
        assertEquals(REPORTER, scoring.deltas().getFirst().owner());
        assertEquals("network", scoring.deltas().getFirst().section());
        assertEquals(50, scoring.deltas().getFirst().delta());
        assertEquals(EloReasonCode.REPORT_REWARDED, scoring.deltas().getFirst().reason());
        assertTrue(bed.profiles.repository().findById(REPORTED).isPresent(), "the punishment was applied to the reported player");
    }

    @Test
    void aResolutionWithAnUnknownTemplateIsATypedErrorDistinctFromAMissingReport() {
        UUID id = givenReport(ReportCategory.CHEATING);

        var result = assertInstanceOf(ReportService.ResolveResult.PunishmentRejected.class, resolveWithTemplate(id, UUID.randomUUID()));

        assertEquals(ErrorKind.VALIDATION, result.kind(), "a missing template is a problem of the request, not a missing report");
        assertFalse(result.message().isBlank(), "the error carries a message");
        assertEquals(ReportStatus.OPEN, reports.repository().findById(id).orElseThrow().getStatus(), "no partial completion");
        assertTrue(scoring.deltas().isEmpty());
    }

    @Test
    void resolvingAMissingReportIsNotFound() {
        assertInstanceOf(ReportService.ResolveResult.NotFound.class, resolve(UUID.randomUUID(), ReportStatus.ACTIONED));
    }

    @Test
    void anActionedReportWithATemplateDoesNotApplyTheActionedDeltaOnTop() {
        UUID id = givenReport(ReportCategory.CHEATING);
        var template = bed.givenTemplate("grief", "network", null, PunishmentTestBed.step("P3D", 100));

        resolveWithTemplate(id, template.getIdentifier());

        assertTrue(scoring.deltas().stream().noneMatch(d -> d.reason() == EloReasonCode.REPORT_ACTIONED),
                "the template's step cost is the only deduction");
        assertEquals(900, bed.elo.score(REPORTED, "network").orElseThrow(), "the step cost was deducted once");
    }

    @Test
    void aTemplateOnADismissedReportIsRejectedAndNothingIsApplied() {
        UUID id = givenReport(ReportCategory.CHEATING);
        var template = bed.givenTemplate("grief", "network", null, PunishmentTestBed.step("P3D", 100));

        var result = assertInstanceOf(ReportService.ResolveResult.TemplateNotAllowed.class,
                service.resolve(id, new ReportResolutionDTO(ReportStatus.DISMISSED, "note", UUID.randomUUID(), template.getIdentifier(), UUID.randomUUID())));

        assertFalse(result.message().isBlank());
        assertFalse(bed.profiles.repository().findById(REPORTED).isPresent(), "no punishment was applied");
        assertEquals(ReportStatus.OPEN, reports.repository().findById(id).orElseThrow().getStatus());
    }

    @Test
    void aResolvedReportCannotBeResolvedAgain() {
        UUID id = givenReport(ReportCategory.CHEATING);
        resolve(id, ReportStatus.ACTIONED);
        scoring.deltas().clear();

        assertInstanceOf(ReportService.ResolveResult.AlreadyResolved.class, resolve(id, ReportStatus.ACTIONED));

        assertTrue(scoring.deltas().isEmpty(), "the standing ELO effects must not repeat");
    }

    @Test
    void aSecondResolutionWithATemplateDoesNotPunishAgain() {
        UUID id = givenReport(ReportCategory.CHEATING);
        var template = bed.givenTemplate("grief", "network", null, PunishmentTestBed.step("P3D", 100));
        resolveWithTemplate(id, template.getIdentifier());
        int punishmentsBefore = bed.punishments.all().size();

        assertInstanceOf(ReportService.ResolveResult.AlreadyResolved.class, resolveWithTemplate(id, template.getIdentifier()));

        assertEquals(punishmentsBefore, bed.punishments.all().size(), "no second punishment");
    }

    @Test
    void theResolutionTimestampComesFromTheInjectedClock() {
        UUID id = givenReport(ReportCategory.CHEATING);
        bed.eloBed.clock.advance(Duration.ofHours(5));

        resolve(id, ReportStatus.DISMISSED);

        assertEquals(bed.eloBed.clock.millis(), reports.repository().findById(id).orElseThrow().getUpdatedAt());
    }

    @Test
    void aResolutionWhoseTemplateSectionIsDisabledIsRejectedAndLeavesTheReportOpen() {
        UUID id = givenReport(ReportCategory.CHEATING);
        var template = bed.givenTemplate("grief", "network", null, PunishmentTestBed.step("P3D", 0));
        bed.eloBed.sections.disable("network");

        assertInstanceOf(ReportService.ResolveResult.PunishmentRejected.class, resolveWithTemplate(id, template.getIdentifier()));

        assertEquals(ReportStatus.OPEN, reports.repository().findById(id).orElseThrow().getStatus(), "no partial completion");
        assertTrue(scoring.deltas().isEmpty());
    }

    @Test
    void aReportThatIsNotActionedAppliesNoDelta() {
        UUID id = givenReport(ReportCategory.CHEATING);

        resolve(id, ReportStatus.DISMISSED);

        assertTrue(scoring.deltas().isEmpty(), "only ACTIONED reports score");
    }
}
