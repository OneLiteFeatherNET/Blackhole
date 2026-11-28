package net.onelitefeather.blackhole.velocity.enforcement;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether this proxy enforces punishments. Starts as {@link State#STARTING}; the plugin's initialisation sets it
 * to {@link State#ENFORCING} when it completes, or {@link State#FAILED} with the cause when it does not. Read by
 * the login guard that refuses players while nothing is enforced.
 */
public final class EnforcementHealth {

    public enum State {
        STARTING,
        ENFORCING,
        FAILED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private volatile String failure;

    public void enforcing() {
        this.failure = null;
        this.state.set(State.ENFORCING);
    }

    public void failed(Throwable cause) {
        this.failure = cause.toString();
        this.state.set(State.FAILED);
    }

    public State state() {
        return this.state.get();
    }

    public boolean isEnforcing() {
        return this.state.get() == State.ENFORCING;
    }

    /** @return what the initialisation failed with, empty if it did not fail */
    public Optional<String> failure() {
        return Optional.ofNullable(this.failure);
    }
}
