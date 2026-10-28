package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.cloudnet.CatalogueReporter;
import net.onelitefeather.blackhole.velocity.enforcement.RelayState;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Tells the backend whether this proxy has SignedVelocity loaded, so the admin panel can warn staff while
 * it is missing. The state is sent once shortly after startup and again on every heartbeat: the backend
 * treats a proxy that stops reporting as unknown, so a lost report is repaired by the next one.
 */
public final class ProxyCapabilityReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyCapabilityReporter.class);

    /** Delay before the first report, so the startup log is written first. */
    static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    /** How often the state is sent again; the backend's stale window is twice this. */
    public static final Duration HEARTBEAT = Duration.ofMinutes(5);

    private final RelayState relay;
    private final BackendGateway backend;
    private final CatalogueReporter.Timer timer;
    private final String proxyId;

    public ProxyCapabilityReporter(@NotNull RelayState relay, @NotNull BackendGateway backend, @NotNull CatalogueReporter.Timer timer,
                                   @NotNull String proxyId) {
        this.relay = relay;
        this.backend = backend;
        this.timer = timer;
        this.proxyId = proxyId;
    }

    /** Schedules the first report and the heartbeat. Never blocks: each report runs on the timer's thread. */
    public void start() {
        this.timer.after(INITIAL_DELAY, this::report);
        this.timer.every(HEARTBEAT, this::report);
    }

    void report() {
        try {
            this.backend.reportProxyCapabilities(this.proxyId, this.relay.present(), this.relay.version());
        } catch (ApiException e) {
            LOGGER.warn("Could not report the SignedVelocity state to the backend, will retry at the next heartbeat: {}", e.getMessage());
        }
    }
}
