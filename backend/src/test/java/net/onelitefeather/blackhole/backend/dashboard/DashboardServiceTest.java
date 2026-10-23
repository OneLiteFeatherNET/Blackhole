package net.onelitefeather.blackhole.backend.dashboard;

import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionCache;
import net.onelitefeather.blackhole.backend.section.SectionEntity;
import net.onelitefeather.blackhole.backend.section.SectionRepository;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardServiceTest {

    /** Hand-fed answers; records the arguments the service asked with. */
    private static final class FakeSource implements DashboardSource {
        Map<String, Long> active = Map.of();
        long reports;
        long appeals;
        long escalations;
        List<DashboardDTO.Escalation> latest = List.of();
        long askedNow = -1;
        long askedSince = -1;
        int askedLimit = -1;

        @Override
        public Map<String, Long> activePunishmentsBySection(long now) {
            this.askedNow = now;
            return this.active;
        }

        @Override
        public long openReports() {
            return this.reports;
        }

        @Override
        public long pendingAppeals() {
            return this.appeals;
        }

        @Override
        public long escalationsSince(long since) {
            this.askedSince = since;
            return this.escalations;
        }

        @Override
        public List<DashboardDTO.Escalation> latestEscalations(int limit) {
            this.askedLimit = limit;
            return this.latest;
        }
    }

    private final TestClock clock = new TestClock();
    private final FakeSource source = new FakeSource();
    private final InMemoryRepository<SectionRepository, SectionEntity, String> sections =
            InMemoryRepository.<SectionRepository, SectionEntity, String>of(SectionRepository.class, SectionEntity::getSectionKey).build();
    private final SectionService sectionService = new SectionService(
            sections.repository(), new SectionCache(new InMemorySharedCache(), Duration.ofMinutes(5)),
            new RecordingEventPublisher(), key -> { });
    private final DashboardService service = new DashboardService(source, sectionService, clock);

    private void givenSection(String key) {
        sectionService.create(new SectionDTO.CreateRequest(
                key, key, EnforcementKind.BAN, null, null, 1000, 700, "P3D", 300, 700, "banned", null));
    }

    @Test
    void everyEnabledSectionIsListedEvenWithoutActivePunishments() {
        givenSection("chat");
        givenSection("network");
        source.active = Map.of("network", 4L);

        DashboardDTO.Response summary = service.summary();

        assertEquals(List.of(new DashboardDTO.SectionCount("chat", 0), new DashboardDTO.SectionCount("network", 4)),
                summary.activePunishmentsBySection(), "sections sorted by key, zero-filled");
    }

    @Test
    void aDisabledSectionOnlyShowsWhilePunishmentsAreStillRunning() {
        givenSection("chat");
        givenSection("old");
        sectionService.disable("chat");
        sectionService.disable("old");
        source.active = Map.of("old", 2L);

        DashboardDTO.Response summary = service.summary();

        assertEquals(List.of(new DashboardDTO.SectionCount("old", 2)), summary.activePunishmentsBySection(),
                "a disabled section without active punishments is hidden");
    }

    @Test
    void countsAreTakenFromTheSource() {
        source.reports = 3;
        source.appeals = 5;
        source.escalations = 7;
        List<DashboardDTO.Escalation> latest = new ArrayList<>(List.of(
                new DashboardDTO.Escalation("p1", "aa".repeat(64), "network", 1L, false)));
        source.latest = latest;

        DashboardDTO.Response summary = service.summary();

        assertEquals(3, summary.openReports(), "open reports");
        assertEquals(5, summary.pendingAppeals(), "pending appeals");
        assertEquals(7, summary.escalationsLast7Days(), "escalations in the last 7 days");
        assertEquals(latest, summary.latestEscalations(), "latest escalations pass through");
    }

    @Test
    void theEscalationWindowIsSevenDaysBeforeTheInjectedNow() {
        long now = clock.millis();

        service.summary();

        assertEquals(now, source.askedNow, "expiry is judged at the clock's now");
        assertEquals(now - Duration.ofDays(7).toMillis(), source.askedSince, "7 day window");
        assertEquals(DashboardService.LATEST_ESCALATIONS, source.askedLimit, "latest list size");
    }

    @Test
    void theWindowMovesWithTheClock() {
        clock.advance(Duration.ofDays(30));

        service.summary();

        assertEquals(clock.millis() - Duration.ofDays(7).toMillis(), source.askedSince, "window follows the clock");
    }
}
