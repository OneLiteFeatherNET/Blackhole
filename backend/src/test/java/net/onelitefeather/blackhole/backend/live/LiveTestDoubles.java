package net.onelitefeather.blackhole.backend.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * In-memory stand-ins for the live hub's ports. Every test builds its own, so nothing is shared between tests.
 */
final class LiveTestDoubles {

    private LiveTestDoubles() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Virtual time: a task runs when {@link #advance} passes its due time. No thread, no sleep. */
    static final class ManualScheduler implements LiveScheduler {

        private final List<Task> tasks = new ArrayList<>();
        private Duration now = Duration.ZERO;

        @Override
        public Handle schedule(Duration delay, Runnable task) {
            Task scheduled = new Task(this.now.plus(delay), task);
            this.tasks.add(scheduled);
            return scheduled::cancel;
        }

        /** Runs every task due within {@code by}, in due order; a task may schedule more. */
        void advance(Duration by) {
            Duration target = this.now.plus(by);
            while (true) {
                Task next = this.tasks.stream().filter(task -> !task.cancelled && task.due.compareTo(target) <= 0)
                        .min((a, b) -> a.due.compareTo(b.due)).orElse(null);
                if (next == null) {
                    break;
                }
                this.tasks.remove(next);
                this.now = next.due;
                next.run.run();
            }
            this.now = target;
        }

        int pending() {
            return (int) this.tasks.stream().filter(task -> !task.cancelled).count();
        }

        private static final class Task {
            final Duration due;
            final Runnable run;
            boolean cancelled;

            Task(Duration due, Runnable run) {
                this.due = due;
                this.run = run;
            }

            void cancel() {
                this.cancelled = true;
            }
        }
    }

    /** The current seq per case and audience, set by the test. */
    static final class FixedSequence implements LiveSequence {

        private final Map<String, Long> seqs = new HashMap<>();

        void set(String caseKey, LiveAudience audience, long seq) {
            this.seqs.put(caseKey + "/" + audience, seq);
        }

        @Override
        public long current(String caseKey, LiveAudience audience) {
            return this.seqs.getOrDefault(caseKey + "/" + audience, 0L);
        }
    }

    /** The sessions that are alive; a session not in the set has ended. */
    static final class FakeSessions implements LiveSessions {

        private final Set<String> alive = new HashSet<>();

        void live(String sessionKey) {
            this.alive.add(sessionKey);
        }

        void end(String sessionKey) {
            this.alive.remove(sessionKey);
        }

        @Override
        public boolean alive(String sessionKey) {
            return this.alive.contains(sessionKey);
        }
    }

    /** A transport that hands every signal to every listener, like one shared bus with several replicas attached. */
    static final class SharedTransport implements ChangeSignalTransport {

        private final List<Consumer<ChangeSignal>> listeners = new ArrayList<>();
        final List<ChangeSignal> published = new ArrayList<>();

        @Override
        public void publish(ChangeSignal signal) {
            this.published.add(signal);
            for (Consumer<ChangeSignal> listener : List.copyOf(this.listeners)) {
                listener.accept(signal);
            }
        }

        @Override
        public void subscribe(Consumer<ChangeSignal> listener) {
            this.listeners.add(listener);
        }
    }

    /** The frames and the end of one stream, as the HTTP layer would see them. */
    static final class Collector implements Consumer<LiveFrame> {

        final List<LiveFrame> frames = new ArrayList<>();
        boolean ended;

        @Override
        public void accept(LiveFrame frame) {
            this.frames.add(frame);
        }

        void end() {
            this.ended = true;
        }
    }
}
