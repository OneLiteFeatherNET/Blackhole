package net.onelitefeather.blackhole.velocity.cloudnet;

import org.slf4j.Logger;

import java.time.Duration;

/**
 * Keeps the backend's CloudNet catalogue of this proxy fresh: a full snapshot at start, one
 * coalesced push (trailing edge, {@code debounce} after the first change) when CloudNet reports a
 * change, and a keep-alive push every {@code heartbeat}. A failed push is only logged: the next
 * change or heartbeat sends a full snapshot again, and enforcement never depends on it.
 */
public final class CatalogueReporter {

    /** Runs tasks later; Velocity's scheduler in production, a hand-driven one in tests. */
    public interface Timer {

        /** Runs {@code task} once after {@code delay}. */
        void after(Duration delay, Runnable task);

        /** Runs {@code task} every {@code period}, first after one period. */
        void every(Duration period, Runnable task);
    }

    private final CloudNetView view;
    private final CatalogueSink sink;
    private final Timer timer;
    private final Logger logger;
    private final String proxyId;
    private final Duration debounce;
    private final Duration heartbeat;
    private boolean pending;

    /**
     * @param proxyId   the id the backend files the snapshot under
     * @param debounce  how long after the first change the coalesced push goes out
     * @param heartbeat the keep-alive period
     */
    public CatalogueReporter(CloudNetView view, CatalogueSink sink, Timer timer, Logger logger,
                             String proxyId, Duration debounce, Duration heartbeat) {
        this.view = view;
        this.sink = sink;
        this.timer = timer;
        this.logger = logger;
        this.proxyId = proxyId;
        this.debounce = debounce;
        this.heartbeat = heartbeat;
    }

    /** Pushes once now, then on changes and heartbeats. Does nothing if CloudNet is not available. */
    public void start() {
        if (!this.view.available()) {
            return;
        }
        this.view.onChange(this::changed);
        this.timer.every(this.heartbeat, this::push);
        this.timer.after(Duration.ZERO, this::push);
    }

    private void changed() {
        synchronized (this) {
            if (this.pending) {
                return;
            }
            this.pending = true;
        }
        this.timer.after(this.debounce, () -> {
            synchronized (this) {
                this.pending = false;
            }
            push();
        });
    }

    private void push() {
        try {
            this.sink.report(this.proxyId, this.view.catalogue());
        } catch (Exception e) {
            this.logger.warn("Could not report the CloudNet catalogue to the backend, will retry: {}", e.getMessage());
        }
    }
}
