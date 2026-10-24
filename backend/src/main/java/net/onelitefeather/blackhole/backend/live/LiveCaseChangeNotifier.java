package net.onelitefeather.blackhole.backend.live;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChangeNotifier;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a case write into a content-free {@link ChangeSignal} on the transport. An event of a type the player reads (the portal's
 * allow-list, and the two lifecycle markers that any visibility shows) is {@link LiveAudience#PLAYER}; every other event, and the
 * seen marker, is {@link LiveAudience#STAFF}. A failed publish is counted in {@link #FAILURES} and logged without content; the write
 * that caused it has already succeeded.
 */
@Singleton
public class LiveCaseChangeNotifier implements CaseChangeNotifier {

    /** Counts signals that could not be published; the only tag-free meter of the live path. */
    public static final String FAILURES = "blackhole.live.signal.failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveCaseChangeNotifier.class);

    private final ChangeSignalTransport transport;
    private final MeterRegistry meters;

    public LiveCaseChangeNotifier(ChangeSignalTransport transport, MeterRegistry meters) {
        this.transport = transport;
        this.meters = meters;
    }

    @Override
    public void appended(String punishmentIdentifier, long seq, String type) {
        publish(ChangeSignal.changed(punishmentIdentifier, seq, audienceOf(type)));
    }

    @Override
    public void sessionsEnded(String punishmentIdentifier) {
        publish(ChangeSignal.sessionsEnded(punishmentIdentifier));
    }

    @Override
    public void seenMoved(String punishmentIdentifier, long seq) {
        publish(ChangeSignal.changed(punishmentIdentifier, seq, LiveAudience.STAFF));
    }

    /** Who may hear of an event of this type: the player's allow-list and its lifecycle markers, else staff only. */
    static LiveAudience audienceOf(String type) {
        boolean playerReads = CaseEventPayload.PORTAL_VISIBLE_TYPES.contains(type) || CaseEventPayload.PORTAL_ANY_VISIBILITY_TYPES.contains(type);
        return playerReads ? LiveAudience.PLAYER : LiveAudience.STAFF;
    }

    private void publish(ChangeSignal signal) {
        try {
            this.transport.publish(signal);
        } catch (RuntimeException e) {
            this.meters.counter(FAILURES).increment();
            LOGGER.warn("A live {} signal could not be published; clients catch up by polling", signal.kind(), e);
        }
    }
}
