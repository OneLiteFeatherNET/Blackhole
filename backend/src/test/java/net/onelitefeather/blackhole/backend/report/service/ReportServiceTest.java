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
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final ReportService service = new ReportService(
            reports.repository(), new RecordingEventPublisher(), null, scoring, mapping, 5, 50, Duration.ofMinutes(10), -100, 50);

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

    @Test
    void aReportThatIsNotActionedAppliesNoDelta() {
        UUID id = givenReport(ReportCategory.CHEATING);

        resolve(id, ReportStatus.DISMISSED);

        assertTrue(scoring.deltas().isEmpty(), "only ACTIONED reports score");
    }
}
