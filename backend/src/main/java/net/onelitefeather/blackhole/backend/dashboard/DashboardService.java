package net.onelitefeather.blackhole.backend.dashboard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles the admin dashboard from read-only queries. Every enabled section is listed even when
 * nobody is punished in it, so the dashboard shape does not depend on the data.
 */
@Singleton
public class DashboardService {

    static final Duration ESCALATION_WINDOW = Duration.ofDays(7);
    static final int LATEST_ESCALATIONS = 10;

    private final DashboardSource source;
    private final SectionService sectionService;
    private final Clock clock;

    public DashboardService(DashboardSource source, SectionService sectionService, Clock clock) {
        this.source = source;
        this.sectionService = sectionService;
        this.clock = clock;
    }

    public DashboardDTO.Response summary() {
        long now = this.clock.millis();
        Map<String, Long> active = this.source.activePunishmentsBySection(now);
        List<DashboardDTO.SectionCount> counts = new ArrayList<>();
        for (SectionDTO.Response section : this.sectionService.list()) {
            long held = active.getOrDefault(section.key(), 0L);
            // A disabled section only shows while punishments in it are still running.
            if (section.enabled() || held > 0) {
                counts.add(new DashboardDTO.SectionCount(section.key(), held));
            }
        }
        return new DashboardDTO.Response(
                counts,
                this.source.openReports(),
                this.source.pendingAppeals(),
                this.source.escalationsSince(now - ESCALATION_WINDOW.toMillis()),
                this.source.latestEscalations(LATEST_ESCALATIONS)
        );
    }
}
