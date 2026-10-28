package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stops calling the scorer after repeated failures. Closed: calls go through. Open, after
 * {@code failureThreshold} failures inside {@code failureWindow}: calls are refused until {@code openFor} has
 * passed. Half-open: one probe call decides, success closes the breaker and failure opens it again.
 *
 * <p>All time comes from the injected clock.
 */
public final class ScorerCircuitBreaker {

    public enum State {
        CLOSED,
        HALF_OPEN,
        OPEN
    }

    private final Clock clock;
    private final int failureThreshold;
    private final Duration failureWindow;
    private final Duration openFor;
    private final Deque<Instant> failures = new ArrayDeque<>();

    private State state = State.CLOSED;
    private Instant openedAt;
    private boolean probeInFlight;

    public ScorerCircuitBreaker(Clock clock, int failureThreshold, Duration failureWindow, Duration openFor) {
        this.clock = clock;
        this.failureThreshold = failureThreshold;
        this.failureWindow = failureWindow;
        this.openFor = openFor;
    }

    public synchronized boolean allowCall() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (Duration.between(openedAt, clock.instant()).compareTo(openFor) < 0) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            case HALF_OPEN:
            default:
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
        }
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            probeInFlight = false;
            failures.clear();
        }
    }

    public synchronized void recordFailure() {
        Instant now = clock.instant();
        if (state == State.HALF_OPEN) {
            open(now);
            return;
        }
        if (state == State.OPEN) {
            return;
        }
        failures.addLast(now);
        while (!failures.isEmpty() && Duration.between(failures.peekFirst(), now).compareTo(failureWindow) > 0) {
            failures.removeFirst();
        }
        if (failures.size() >= failureThreshold) {
            open(now);
        }
    }

    public synchronized State state() {
        return state;
    }

    private void open(Instant now) {
        state = State.OPEN;
        openedAt = now;
        probeInFlight = false;
        failures.clear();
    }
}
