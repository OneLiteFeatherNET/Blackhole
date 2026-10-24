package net.onelitefeather.blackhole.backend.live;

import java.util.function.Consumer;

/**
 * The port between the case writers and the live hubs. The hub depends only on this interface: the in-process adapter serves one
 * replica and tests, a bus adapter serves several replicas (a transient, non-durable subscription: a signal older than the stream
 * is useless). {@link #publish} may fail; the caller counts the failure and never fails the write.
 */
public interface ChangeSignalTransport {

    /** Hands the signal to every subscriber, on every replica. */
    void publish(ChangeSignal signal);

    /** Registers a listener for the signals of every replica. */
    void subscribe(Consumer<ChangeSignal> listener);
}
