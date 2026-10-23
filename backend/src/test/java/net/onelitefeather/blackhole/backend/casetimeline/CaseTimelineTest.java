package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.support.CaseTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseTimelineTest {

    private static final String PUNISHMENT = "p-1";

    private final TestClock clock = new TestClock();
    private final CaseTestBed bed = new CaseTestBed(clock);

    private CaseEventDraft expired(String dedupeKey, long occurredAt) {
        return new CaseEventDraft(PUNISHMENT, new CaseEventPayload.Expired(occurredAt), occurredAt, CaseEventOrigin.LIVE,
                CaseActor.system(), null, dedupeKey);
    }

    @Test
    void recordingTheSameFactTwiceStoresOneEvent() {
        assertTrue(bed.timeline.record(expired("expired:p-1", 10)), "the first recording is stored");
        assertFalse(bed.timeline.record(expired("expired:p-1", 10)), "the second one is a no-op");

        assertEquals(1, bed.log.timeline(PUNISHMENT).size(), "one row, however often it is recorded");
    }

    @Test
    void twoFactsWithDifferentDedupeKeysAreBothStored() {
        bed.timeline.record(expired("expired:p-1", 10));
        bed.timeline.record(expired("other-key", 11));

        assertEquals(2, bed.log.timeline(PUNISHMENT).size());
    }

    @Test
    void aFailingLogDoesNotThrowButIsCounted() {
        bed.log.failAppends(new IllegalStateException("database down"));

        boolean recorded = bed.timeline.record(expired("expired:p-1", 10));

        assertFalse(recorded, "nothing was stored");
        assertEquals(1.0, bed.failures(), "the failure is counted in blackhole.case.record.failures");
    }

    @Test
    void aFactThatCannotBeBuiltDoesNotThrowButIsCounted() {
        bed.timeline.applied(null, null, null);

        assertEquals(1.0, bed.failures(), "building the event failed, which is a failed recording");
        assertTrue(bed.log.all().isEmpty(), "and nothing was stored");
    }

    @Test
    void theTimelineIsOrderedByOccurredAtThenBySeq() {
        bed.timeline.record(expired("a", 30));
        bed.timeline.record(expired("b", 10));
        bed.timeline.record(expired("c", 10));
        bed.timeline.record(expired("d", 20));

        List<String> order = bed.log.timeline(PUNISHMENT).stream().map(CaseEventRecord::dedupeKey).toList();

        assertEquals(List.of("b", "c", "d", "a"), order, "equal times keep the order they were appended in");
    }
}
