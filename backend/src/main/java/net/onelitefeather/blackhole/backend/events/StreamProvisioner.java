package net.onelitefeather.blackhole.backend.events;

import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates or updates the stream {@value NatsStreams#STREAM} and the durable consumers this process
 * owns. Idempotent. A failure is logged and never stops the application: the bus is an additive side
 * channel, and the work is retried (when a publish fails, and by every consumer loop).
 */
@Singleton
public class StreamProvisioner {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamProvisioner.class);
    /** The API error code of "stream not found". */
    private static final int STREAM_NOT_FOUND = 10059;

    private final NatsConnectionProvider connections;
    private final NatsSettings settings;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean streamReady;

    public StreamProvisioner(NatsConnectionProvider connections, NatsSettings settings) {
        this.connections = connections;
        this.settings = settings;
    }

    @EventListener
    void onStartup(StartupEvent event) {
        ensureStreamInBackground();
    }

    /**
     * Creates or updates the stream. Blocks for the NATS round trips, so never call it from a request thread.
     *
     * @return whether the stream exists afterwards
     */
    public synchronized boolean ensureStream() {
        if (this.streamReady) {
            return true;
        }
        try {
            JetStreamManagement management = this.connections.await(this.settings.connectTimeout()).jetStreamManagement();
            boolean exists = true;
            try {
                management.getStreamInfo(NatsStreams.STREAM);
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() != STREAM_NOT_FOUND) {
                    throw e;
                }
                exists = false;
            }
            if (exists) {
                try {
                    management.updateStream(NatsStreams.stream(this.settings));
                } catch (JetStreamApiException e) {
                    // The stream is there; a settings update the credentials may not be allowed to do is not fatal.
                    LOGGER.warn("Could not update stream {}: {}", NatsStreams.STREAM, e.getMessage());
                }
            } else {
                management.addStream(NatsStreams.stream(this.settings));
                LOGGER.info("Created JetStream stream {}", NatsStreams.STREAM);
            }
            this.streamReady = true;
            return true;
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            LOGGER.warn("Could not provision stream {}: {}", NatsStreams.STREAM, e.getMessage());
            return false;
        }
    }

    /** Creates or updates the durable consumer; blocks like {@link #ensureStream()}. */
    public void ensureConsumer(EventConsumer consumer) {
        try {
            this.connections.await(this.settings.connectTimeout()).jetStreamManagement()
                    .addOrUpdateConsumer(NatsStreams.STREAM, NatsStreams.consumer(consumer));
        } catch (IOException | JetStreamApiException e) {
            throw new IllegalStateException("Could not provision consumer " + consumer.name() + ": " + e.getMessage(), e);
        }
    }

    /** Forgets that the stream was verified, so the next {@link #ensureStream()} checks it again. */
    public void markStale() {
        this.streamReady = false;
    }

    /** Runs {@link #ensureStream()} on its own thread, at most one at a time. */
    public void ensureStreamInBackground() {
        if (this.streamReady || !this.running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("nats-stream-provisioner").start(() -> {
            try {
                ensureStream();
            } finally {
                this.running.set(false);
            }
        });
    }
}
