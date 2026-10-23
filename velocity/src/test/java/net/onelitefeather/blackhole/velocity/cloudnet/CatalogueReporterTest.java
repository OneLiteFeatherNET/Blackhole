package net.onelitefeather.blackhole.velocity.cloudnet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogueReporterTest {

    private static final Duration DEBOUNCE = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT = Duration.ofMinutes(5);

    /** Virtual time: tasks run when the test advances the clock, never on a real timer. */
    private static final class ManualTimer implements CatalogueReporter.Timer {
        private record Once(Duration at, Runnable task) {
        }

        private final List<Once> once = new ArrayList<>();
        private final List<Runnable> repeating = new ArrayList<>();
        private Duration period;
        private Duration now = Duration.ZERO;

        @Override
        public void after(Duration delay, Runnable task) {
            this.once.add(new Once(this.now.plus(delay), task));
        }

        @Override
        public void every(Duration period, Runnable task) {
            this.period = period;
            this.repeating.add(task);
        }

        /** Advances one second at a time so ordering is the same as a real clock. */
        void advance(Duration by) {
            Duration end = this.now.plus(by);
            while (this.now.compareTo(end) < 0) {
                this.now = this.now.plusSeconds(1);
                for (Once due : List.copyOf(this.once)) {
                    if (due.at().compareTo(this.now) <= 0) {
                        this.once.remove(due);
                        due.task().run();
                    }
                }
                if (this.period != null && this.now.toSeconds() % this.period.toSeconds() == 0) {
                    List.copyOf(this.repeating).forEach(Runnable::run);
                }
            }
        }

        /** Runs the tasks due right now (delay zero). */
        void runDue() {
            for (Once due : List.copyOf(this.once)) {
                if (due.at().compareTo(this.now) <= 0) {
                    this.once.remove(due);
                    due.task().run();
                }
            }
        }
    }

    private static final class RecordingSink implements CatalogueSink {
        final List<String> proxyIds = new ArrayList<>();
        final List<Catalogue> sent = new ArrayList<>();
        boolean failing;

        @Override
        public void report(String proxyId, Catalogue catalogue) throws Exception {
            if (this.failing) {
                throw new Exception("backend down");
            }
            this.proxyIds.add(proxyId);
            this.sent.add(catalogue);
        }
    }

    private static Catalogue catalogueWith(String task) {
        return new Catalogue(List.of(new Catalogue.Task(task, Set.of())), List.of(), List.of());
    }

    private ManualTimer timer;
    private RecordingSink sink;
    private FakeCloudNetView view;

    @BeforeEach
    void setUp() {
        this.timer = new ManualTimer();
        this.sink = new RecordingSink();
        this.view = new FakeCloudNetView(true);
    }

    private CatalogueReporter reporter() {
        return new CatalogueReporter(this.view, this.sink, this.timer, NOPLogger.NOP_LOGGER, "Proxy-1", DEBOUNCE, HEARTBEAT);
    }

    @Test
    void startPushesTheFullSnapshotUnderTheProxyId() {
        this.view.change(catalogueWith("BedWars"));
        reporter().start();

        this.timer.runDue();

        assertEquals(List.of("Proxy-1"), this.sink.proxyIds);
        assertEquals(List.of(catalogueWith("BedWars")), this.sink.sent);
    }

    @Test
    void nothingIsPushedWhenCloudNetIsNotAvailable() {
        this.view = new FakeCloudNetView(false);
        reporter().start();

        this.timer.advance(Duration.ofMinutes(10));

        assertEquals(0, this.sink.sent.size(), "no CloudNet, no push");
        assertEquals(0, this.view.listeners(), "and no listener registered");
    }

    @Test
    void aBurstOfChangesIsOnePushFiveSecondsAfterTheFirst() {
        reporter().start();
        this.timer.runDue();
        this.sink.sent.clear();

        this.view.change(catalogueWith("A"));
        this.timer.advance(Duration.ofSeconds(2));
        this.view.change(catalogueWith("B"));
        this.view.change(catalogueWith("C"));
        this.timer.advance(Duration.ofSeconds(2));
        assertEquals(0, this.sink.sent.size(), "still inside the debounce window");
        this.timer.advance(Duration.ofSeconds(1));

        assertEquals(List.of(catalogueWith("C")), this.sink.sent, "one push carrying the latest state");
    }

    @Test
    void aChangeAfterAPushStartsANewWindow() {
        reporter().start();
        this.timer.runDue();
        this.sink.sent.clear();

        this.view.change(catalogueWith("A"));
        this.timer.advance(DEBOUNCE);
        this.view.change(catalogueWith("B"));
        this.timer.advance(DEBOUNCE);

        assertEquals(List.of(catalogueWith("A"), catalogueWith("B")), this.sink.sent);
    }

    @Test
    void theHeartbeatPushesEveryFiveMinutesWithoutChanges() {
        reporter().start();
        this.timer.runDue();
        this.sink.sent.clear();

        this.timer.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertEquals(2, this.sink.sent.size(), "one keep-alive per five minutes");
    }

    @Test
    void aFailedPushIsRetriedByTheNextHeartbeatAndDoesNotThrow() {
        reporter().start();
        this.sink.failing = true;
        this.timer.runDue();
        this.view.change(catalogueWith("A"));
        this.timer.advance(DEBOUNCE);
        assertEquals(0, this.sink.sent.size());

        this.sink.failing = false;
        this.timer.advance(HEARTBEAT);

        assertEquals(1, this.sink.sent.size(), "the heartbeat resends the full snapshot");
        assertEquals(List.of(catalogueWith("A")), this.sink.sent);
    }
}
