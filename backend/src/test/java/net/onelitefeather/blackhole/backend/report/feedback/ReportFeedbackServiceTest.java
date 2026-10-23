package net.onelitefeather.blackhole.backend.report.feedback;

import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.report.feedback.dto.ReportFeedbackDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportFeedbackServiceTest {

    private static final String REPORTER = "bb".repeat(64);
    private static final String OTHER = "cc".repeat(64);
    private static final UUID REPORT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Duration TTL = Duration.ofDays(14);

    private final TestClock clock = new TestClock();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final List<String> poked = new ArrayList<>();
    private final InMemoryRepository<ReportFeedbackRepository, ReportFeedbackEntity, UUID> store =
            InMemoryRepository.<ReportFeedbackRepository, ReportFeedbackEntity, UUID>of(ReportFeedbackRepository.class, ReportFeedbackEntity::getIdentifier)
                    .assigningIds(entity -> entity.withIdentifier(UUID.randomUUID()))
                    .finder("findByReporterHashAndExpiresAtGreaterThanOrderByCreatedAt", args -> {
                        String owner = (String) args[0];
                        long now = (long) args[1];
                        return this.store().all().stream()
                                .filter(row -> row.getReporterHash().equals(owner) && row.getExpiresAt() > now)
                                .sorted(java.util.Comparator.comparingLong(ReportFeedbackEntity::getCreatedAt)).toList();
                    })
                    .finder("deleteByExpiresAtLessThanEquals", args -> {
                        long now = (long) args[0];
                        List<ReportFeedbackEntity> expired = this.store().all().stream().filter(row -> row.getExpiresAt() <= now).toList();
                        expired.forEach(row -> this.store().repository().deleteById(row.getIdentifier()));
                        return (long) expired.size();
                    })
                    .build();
    private final ReportFeedbackService service = new ReportFeedbackService(store.repository(), poked::add, events, clock, TTL);

    private InMemoryRepository<ReportFeedbackRepository, ReportFeedbackEntity, UUID> store() {
        return this.store;
    }

    private static ReportEntity report(String reporter) {
        return new ReportEntity(reporter, "aa".repeat(64), ReportCategory.CHEATING, "d", new ArrayList<>(), ReportStatus.ACTIONED,
                0L, 0L, null, null, new HashMap<>());
    }

    @Test
    void aResolvedReportQueuesAMessageForItsReporterOnly() {
        service.queue(REPORT, report(REPORTER), ReportStatus.ACTIONED);

        ReportFeedbackDTO.ListResponse mine = service.pending(REPORTER);

        assertEquals(1, mine.items().size());
        assertEquals(ReportStatus.ACTIONED, mine.items().getFirst().outcome());
        assertEquals(REPORT, mine.items().getFirst().reportIdentifier());
        assertEquals(ReportCategory.CHEATING, mine.items().getFirst().category());
        assertTrue(service.pending(OTHER).items().isEmpty(), "somebody else's reporter must not see it");
    }

    @Test
    void theProxiesArePokedAndAnEventWithHashesOnlyIsPublished() {
        service.queue(REPORT, report(REPORTER), ReportStatus.DISMISSED);

        assertEquals(List.of(REPORTER), poked);
        var event = events.eventsOfType("report.feedback").getFirst();
        assertEquals(REPORTER, event.payload().get("reporterHash"));
        assertEquals("DISMISSED", event.payload().get("outcome"));
        assertTrue(event.payload().values().stream().noneMatch(value -> String.valueOf(value).contains("punishment")),
                "the event must not carry punishment details");
    }

    @Test
    void aStatusThatIsNotAFinalOutcomeQueuesNothing() {
        service.queue(REPORT, report(REPORTER), ReportStatus.UNDER_REVIEW);

        assertTrue(store.all().isEmpty());
        assertTrue(poked.isEmpty());
    }

    @Test
    void anAcknowledgedMessageIsGoneAndAcknowledgingTwiceIsFine() {
        service.queue(REPORT, report(REPORTER), ReportStatus.ACTIONED);
        UUID id = service.pending(REPORTER).items().getFirst().identifier();

        service.acknowledge(id);
        service.acknowledge(id);

        assertTrue(service.pending(REPORTER).items().isEmpty());
    }

    @Test
    void anExpiredMessageIsNotReturnedAndIsPurged() {
        service.queue(REPORT, report(REPORTER), ReportStatus.ACTIONED);
        clock.advance(TTL.plusSeconds(1));

        assertTrue(service.pending(REPORTER).items().isEmpty(), "an expired message must not be delivered");
        assertEquals(1, service.purgeExpired());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void aMessageThatHasNotExpiredIsKeptByThePurge() {
        service.queue(REPORT, report(REPORTER), ReportStatus.ACTIONED);
        clock.advance(TTL.minusSeconds(1));

        assertEquals(0, service.purgeExpired());
        assertEquals(1, service.pending(REPORTER).items().size());
    }

    @Test
    void messagesComeOldestFirst() {
        service.queue(UUID.fromString("00000000-0000-4000-8000-0000000000a1"), report(REPORTER), ReportStatus.ACTIONED);
        clock.advance(Duration.ofMinutes(1));
        service.queue(UUID.fromString("00000000-0000-4000-8000-0000000000a2"), report(REPORTER), ReportStatus.DISMISSED);

        List<ReportFeedbackDTO.Response> items = service.pending(REPORTER).items();

        assertEquals(UUID.fromString("00000000-0000-4000-8000-0000000000a1"), items.get(0).reportIdentifier());
        assertEquals(UUID.fromString("00000000-0000-4000-8000-0000000000a2"), items.get(1).reportIdentifier());
    }
}
