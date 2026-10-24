package net.onelitefeather.blackhole.velocity.nats;

import org.jetbrains.annotations.NotNull;

/**
 * The proxy's subscribe-only link to the event bus. It is a seam: {@link NatsSyncService} only sees the
 * {@link Listener} callbacks, so its decisions can be tested without a NATS client or a server.
 */
public interface BusClient extends AutoCloseable {

    /** What the link tells the proxy. */
    interface Listener {

        /**
         * The subscriptions are in place on a live connection: events are flowing again. Called on the
         * first connect and after every reconnect.
         */
        void connected();

        /**
         * The connection was lost (or re-established but not yet subscribed again): events may have been
         * missed, so nothing cached from them can be trusted.
         */
        void disconnected();

        /**
         * One event.
         *
         * @param subject the NATS subject
         * @param body    the message body
         */
        void message(@NotNull String subject, byte @NotNull [] body);
    }

    /**
     * Starts connecting in the background and returns at once; the proxy never waits for the bus.
     *
     * @return false if the link could not be started at all (for example an unreadable credentials file),
     * in which case the proxy stays on HTTP checks
     */
    boolean start(@NotNull Listener listener);

    @Override
    void close();
}
