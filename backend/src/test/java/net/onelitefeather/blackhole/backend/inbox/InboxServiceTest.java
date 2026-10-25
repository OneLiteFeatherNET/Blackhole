package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The inbox service over an in-memory source: counts, the cap, the order and the failure answer. */
class InboxServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

    /** A source that answers from fixed lists; the rules of the SQL are tested against PostgreSQL. */
    private static final class FakeSource implements InboxSource {
        private final Map<InboxSection, List<InboxDTO.Item>> items = new EnumMap<>(InboxSection.class);
        private final Map<InboxSection, Long> counts = new EnumMap<>(InboxSection.class);
        private boolean failing;

        FakeSource with(InboxSection section, long count, List<InboxDTO.Item> rows) {
            this.counts.put(section, count);
            this.items.put(section, rows);
            return this;
        }

        FakeSource failing() {
            this.failing = true;
            return this;
        }

        @Override
        public List<InboxDTO.Item> oldest(InboxSection section, int limit) {
            if (this.failing) {
                throw new InboxUnavailableException("database is down", null);
            }
            return this.items.getOrDefault(section, List.of());
        }

        @Override
        public long count(InboxSection section) {
            return this.counts.getOrDefault(section, 0L);
        }
    }

    private static InboxDTO.Item item(String id, long since) {
        return new InboxDTO.Item(id, null, "ab".repeat(64), since);
    }

    private static InboxDTO.Response response(InboxDTO result) {
        return assertInstanceOf(InboxDTO.Response.class, result, "a readable source answers the response variant");
    }

    @Test
    void theCountOfEachSectionIsTheCountOfItsSource() {
        FakeSource source = new FakeSource()
                .with(InboxSection.NEEDS_ANSWER, 3, List.of())
                .with(InboxSection.OPEN_APPEALS, 7, List.of())
                .with(InboxSection.NEW_REPORTS, 0, List.of())
                .with(InboxSection.UNASSIGNED_CASES, 12, List.of());

        InboxDTO.Response inbox = response(new InboxService(source, CLOCK).summary());

        assertEquals(3, inbox.needsAnswer().count());
        assertEquals(7, inbox.openAppeals().count());
        assertEquals(0, inbox.newReports().count());
        assertEquals(12, inbox.unassignedCases().count());
    }

    @Test
    void aSectionHoldsAtMostTenItemsEvenWhenTheSourceReturnsMore() {
        List<InboxDTO.Item> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(item("r" + i, i));
        }
        FakeSource source = new FakeSource().with(InboxSection.NEW_REPORTS, 12, twelve);

        InboxDTO.Response inbox = response(new InboxService(source, CLOCK).summary());

        assertEquals(10, inbox.newReports().items().size(), "the first ten items only");
        assertEquals(12, inbox.newReports().count(), "the count is still the full count");
    }

    @Test
    void eachSectionListsItsItemsOldestFirst() {
        FakeSource source = new FakeSource().with(InboxSection.OPEN_APPEALS, 3, List.of(
                item("newest", 300), item("oldest", 100), item("middle", 200)));

        InboxDTO.Response inbox = response(new InboxService(source, CLOCK).summary());

        assertEquals(List.of("oldest", "middle", "newest"),
                inbox.openAppeals().items().stream().map(InboxDTO.Item::id).toList());
    }

    @Test
    void theResponseIsStampedWithTheInjectedClock() {
        InboxDTO.Response inbox = response(new InboxService(new FakeSource(), CLOCK).summary());

        assertEquals(CLOCK.millis(), inbox.generatedAt(), "the time comes from the injected clock, not the system");
    }

    @Test
    void aSourceThatCannotBeReadAnswersTheUnavailableErrorInsteadOfThrowing() {
        InboxDTO result = new InboxService(new FakeSource().failing(), CLOCK).summary();

        InboxDTO.Error error = assertInstanceOf(InboxDTO.Error.class, result, "an expected failure is an error variant");
        assertEquals(ErrorKind.UNAVAILABLE, error.kind());
        assertFalse(error.errorMessage().isBlank(), "the error carries a message");
    }
}
