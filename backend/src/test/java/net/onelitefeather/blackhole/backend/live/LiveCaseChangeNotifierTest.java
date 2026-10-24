package net.onelitefeather.blackhole.backend.live;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The notifier maps an event to the audience that may hear of it, and a failing transport never fails the write. */
class LiveCaseChangeNotifierTest {

    private static final String CASE = "PUNISHMENT-7";

    private static final class RecordingTransport implements ChangeSignalTransport {

        final List<ChangeSignal> published = new ArrayList<>();
        boolean failing;

        @Override
        public void publish(ChangeSignal signal) {
            if (this.failing) {
                throw new IllegalStateException("the bus is down");
            }
            this.published.add(signal);
        }

        @Override
        public void subscribe(Consumer<ChangeSignal> listener) {
        }
    }

    @Test
    void aPlayerVisibleMessageSignalsThePlayerAudience() {
        RecordingTransport transport = new RecordingTransport();

        new LiveCaseChangeNotifier(transport, new SimpleMeterRegistry()).appended(CASE, 12, CaseEventPayload.MESSAGE_ADDED);

        assertEquals(List.of(ChangeSignal.changed(CASE, 12, LiveAudience.PLAYER)), transport.published);
    }

    @Test
    void theLifecycleMarkersSignalThePlayerAudienceWhateverTheirVisibility() {
        RecordingTransport transport = new RecordingTransport();
        LiveCaseChangeNotifier notifier = new LiveCaseChangeNotifier(transport, new SimpleMeterRegistry());

        notifier.appended(CASE, 13, CaseEventPayload.APPEAL_FILED);
        notifier.appended(CASE, 14, CaseEventPayload.APPEAL_DECIDED);

        assertEquals(List.of(ChangeSignal.changed(CASE, 13, LiveAudience.PLAYER), ChangeSignal.changed(CASE, 14, LiveAudience.PLAYER)),
                transport.published);
    }

    @Test
    void aStaffOnlyEventSignalsOnlyTheStaffAudience() {
        RecordingTransport transport = new RecordingTransport();

        new LiveCaseChangeNotifier(transport, new SimpleMeterRegistry()).appended(CASE, 15, "note.added");

        assertEquals(List.of(ChangeSignal.changed(CASE, 15, LiveAudience.STAFF)), transport.published);
    }

    @Test
    void aSeenMarkerSignalsOnlyStaffAndASessionsEndSignalsTheCase() {
        RecordingTransport transport = new RecordingTransport();
        LiveCaseChangeNotifier notifier = new LiveCaseChangeNotifier(transport, new SimpleMeterRegistry());

        notifier.seenMoved(CASE, 16);
        notifier.sessionsEnded(CASE);

        assertEquals(List.of(ChangeSignal.changed(CASE, 16, LiveAudience.STAFF), ChangeSignal.sessionsEnded(CASE)), transport.published);
    }

    @Test
    void aFailingSignalIsCountedAndNeverFailsTheWrite() {
        RecordingTransport transport = new RecordingTransport();
        transport.failing = true;
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        assertDoesNotThrow(() -> new LiveCaseChangeNotifier(transport, meters).appended(CASE, 17, CaseEventPayload.MESSAGE_ADDED));

        assertEquals(1.0, meters.counter(LiveCaseChangeNotifier.FAILURES).count());
    }
}
