package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.cloudnet.CatalogueReporter;
import net.onelitefeather.blackhole.velocity.enforcement.RelayState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The relay state is reported once after startup and again on every heartbeat; the timer is driven by hand. */
class ProxyCapabilityReporterTest {

    private static final String PROXY = "Proxy-1";

    /** Runs the one-shot tasks and the periodic tasks when the test says so. */
    private static final class ManualTimer implements CatalogueReporter.Timer {
        final List<Runnable> once = new ArrayList<>();
        final List<Runnable> periodic = new ArrayList<>();

        @Override
        public void after(Duration delay, Runnable task) {
            this.once.add(task);
        }

        @Override
        public void every(Duration period, Runnable task) {
            this.periodic.add(task);
        }

        void startup() {
            List.copyOf(this.once).forEach(Runnable::run);
            this.once.clear();
        }

        void heartbeat() {
            this.periodic.forEach(Runnable::run);
        }
    }

    private final StubGateway backend = new StubGateway();
    private final ManualTimer timer = new ManualTimer();

    @Test
    void startupReportsThePresentRelayAndItsVersion() {
        new ProxyCapabilityReporter(new RelayState(true, "1.5.0"), backend, timer, PROXY).start();

        timer.startup();

        assertEquals(List.of(new StubGateway.RelayReport(PROXY, true, "1.5.0")), backend.relayReports, "one report after startup");
    }

    @Test
    void startupReportsAnAbsentRelayAsAbsent() {
        new ProxyCapabilityReporter(new RelayState(false, null), backend, timer, PROXY).start();

        timer.startup();

        assertEquals(List.of(new StubGateway.RelayReport(PROXY, false, null)), backend.relayReports);
        assertFalse(backend.relayReports.getFirst().present(), "absent is reported as absent, never as present");
    }

    @Test
    void everyHeartbeatReportsTheStateAgain() {
        new ProxyCapabilityReporter(new RelayState(true, "1.5.0"), backend, timer, PROXY).start();
        timer.startup();

        timer.heartbeat();
        timer.heartbeat();

        assertEquals(3, backend.relayReports.size(), "startup plus two heartbeats");
    }

    @Test
    void aFailedReportIsNotThrownAndIsSentAgainAtTheNextHeartbeat() {
        backend.relayReportFailure = new ApiException(503, "backend down");
        new ProxyCapabilityReporter(new RelayState(true, "1.5.0"), backend, timer, PROXY).start();
        timer.startup();
        assertTrue(backend.relayReports.isEmpty(), "the failed call is not recorded as delivered");

        backend.relayReportFailure = null;
        timer.heartbeat();

        assertEquals(List.of(new StubGateway.RelayReport(PROXY, true, "1.5.0")), backend.relayReports, "the next heartbeat delivers it");
    }
}
