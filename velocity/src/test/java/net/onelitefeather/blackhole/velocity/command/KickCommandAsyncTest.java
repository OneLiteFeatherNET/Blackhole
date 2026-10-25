package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.KickAccepted;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.client.model.KickStatus;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kick flow never blocks the calling thread: the backend is called on the async executor and the
 * result is polled on a delay. Executor, delay and clock are fakes the test drives by hand.
 */
class KickCommandAsyncTest {

    private static final Duration EVERY = Duration.ofMillis(500);
    private static final Duration GIVE_UP = Duration.ofSeconds(15);

    private final Queue<Runnable> executorQueue = new ArrayDeque<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private final List<String> backendCalls = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final Queue<KickStatus> statuses = new ArrayDeque<>();
    private ApiException kickFailure;

    private final BackendGateway backend = (BackendGateway) Proxy.newProxyInstance(
            KickCommandAsyncTest.class.getClassLoader(), new Class<?>[]{BackendGateway.class},
            (proxy, method, args) -> {
                backendCalls.add(method.getName());
                return switch (method.getName()) {
                    case "kick", "kickMass" -> {
                        if (kickFailure != null) {
                            throw kickFailure;
                        }
                        yield new KickAccepted().id("k-1").status(KickStatus.PENDING).deadlineAt(clock.millis() + 5000);
                    }
                    case "kickStatus" -> new KickResponse().id("k-1").status(statuses.isEmpty() ? KickStatus.PENDING : statuses.remove())
                            .deliveredCount(1);
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });

    private final KickRunner runner = new KickRunner(this.backend, this.executorQueue::add,
            (delay, task) -> {
                delays.add(delay);
                delayed.add(task);
            }, this.clock, () -> "key-1", EVERY, GIVE_UP);

    private final KickRunner.Listener listener = new KickRunner.Listener() {
        @Override
        public void accepted(KickAccepted accepted) {
            events.add("accepted");
        }

        @Override
        public void settled(KickResponse response) {
            events.add("settled:" + response.getStatus());
        }

        @Override
        public void timedOut(String id) {
            events.add("timeout");
        }

        @Override
        public void failed(ApiException cause) {
            events.add("failed:" + cause.getCode());
        }
    };

    private void drainExecutor() {
        while (!this.executorQueue.isEmpty()) {
            this.executorQueue.remove().run();
        }
    }

    /** Runs the next scheduled poll and whatever it hands to the executor. */
    private void fireNextDelay() {
        this.delayed.removeFirst().run();
        drainExecutor();
    }

    @Test
    void startingAKickCallsNothingOnTheCallingThread() {
        this.runner.kickPlayer("owner", KickScope.NETWORK, null, "calm down", this.listener);

        assertTrue(this.backendCalls.isEmpty(), "nothing ran yet: " + this.backendCalls);
        assertEquals(1, this.executorQueue.size(), "the call was handed to the executor");
    }

    @Test
    void theBackendIsCalledOnTheExecutorThread() {
        this.runner.kickPlayer("owner", KickScope.NETWORK, null, "calm down", this.listener);

        drainExecutor();

        assertEquals(List.of("kick"), this.backendCalls.subList(0, 1));
        assertEquals(List.of("accepted"), this.events);
    }

    @Test
    void thePollWaitsForTheDelayAndNeverRunsOnTheCallingThread() {
        this.runner.kickPlayer("owner", KickScope.NETWORK, null, "calm down", this.listener);
        drainExecutor();

        assertEquals(List.of(EVERY), this.delays, "the first read is scheduled, not made");
        assertEquals(1, this.backendCalls.size(), "no status read before the delay fired");
        assertTrue(this.executorQueue.isEmpty());
    }

    @Test
    void thePollStopsAsSoonAsTheStatusIsSettled() {
        this.statuses.add(KickStatus.PENDING);
        this.statuses.add(KickStatus.DELIVERED);
        this.runner.kickPlayer("owner", KickScope.NETWORK, null, "calm down", this.listener);
        drainExecutor();

        fireNextDelay();
        assertEquals(List.of("accepted"), this.events, "still pending");
        assertEquals(1, this.delayed.size(), "another read is scheduled");
        fireNextDelay();

        assertEquals(List.of("accepted", "settled:DELIVERED"), this.events);
        assertTrue(this.delayed.isEmpty(), "no read is scheduled after the status settled");
    }

    @Test
    void everyOtherNonPendingStatusSettlesToo() {
        for (KickStatus status : List.of(KickStatus.NOT_ON_TARGET, KickStatus.NOT_ONLINE, KickStatus.NO_PROXY_ANSWER)) {
            this.events.clear();
            this.statuses.add(status);
            this.runner.kickPlayer("owner", KickScope.NETWORK, null, "calm down", this.listener);
            drainExecutor();
            fireNextDelay();

            assertEquals(List.of("accepted", "settled:" + status), this.events);
        }
    }

    @Test
    void thePollGivesUpWhenTheLocalDeadlinePassed() {
        this.runner.kickPlayer("owner", KickScope.NETWORK, null, "calm down", this.listener);
        drainExecutor();

        this.clock.advance(GIVE_UP);
        fireNextDelay();

        assertEquals(List.of("accepted", "timeout"), this.events);
        assertTrue(this.delayed.isEmpty(), "the poll ended");
    }

    @Test
    void aRefusedKickIsReportedAndNeverPolled() {
        this.kickFailure = new ApiException(422, "target invalid");
        this.runner.kickPlayer("owner", KickScope.SERVER, "nowhere", "calm down", this.listener);

        drainExecutor();

        assertEquals(List.of("failed:422"), this.events);
        assertTrue(this.delayed.isEmpty());
    }

    @Test
    void aMassKickIsPolledTheSameWay() {
        this.statuses.add(KickStatus.DELIVERED);
        this.runner.kickMass(KickScope.SECTION, "bedwars", "maintenance", this.listener);

        drainExecutor();
        fireNextDelay();

        assertEquals(List.of("kickMass", "kickStatus"), this.backendCalls);
        assertEquals(List.of("accepted", "settled:DELIVERED"), this.events);
    }
}
