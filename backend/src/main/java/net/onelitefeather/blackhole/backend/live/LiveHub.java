package net.onelitefeather.blackhole.backend.live;

import io.micronaut.core.annotation.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The open live streams of one replica, per case. A stream gets {@code retry}, {@code ready} and, when it resumes behind the case,
 * {@code changed}; then a {@code changed} for every signal its audience may hear, a keep-alive every interval and the end: at the
 * lifetime limit, when the session ends (a keep-alive finds it gone, or a {@link ChangeSignal.Kind#SESSIONS_ENDED} signal), when the
 * same session opens another stream, or when the sink fails.
 *
 * <p>The hub holds state only: time comes from the {@link LiveScheduler} and the session and sequence reads from their ports, so
 * a test drives all of it with virtual time. All state changes happen under the hub's monitor; a session read happens outside it.</p>
 */
public final class LiveHub {

    /** The reconnect delay sent once when a stream opens. */
    public static final Duration RETRY = Duration.ofSeconds(5);

    /**
     * The limits of one replica.
     *
     * @param maxStreams   the most open streams; beyond it {@link #open} throws {@link CapacityExceeded}
     * @param keepAlive    how often a keep-alive (and the session check) runs
     * @param maxLifetime  how long a stream may stay open before the client reconnects
     */
    public record Limits(int maxStreams, Duration keepAlive, Duration maxLifetime) {

        public Limits {
            if (maxStreams < 1 || keepAlive.isZero() || keepAlive.isNegative() || maxLifetime.isZero() || maxLifetime.isNegative()) {
                throw new IllegalArgumentException("the live limits must be positive");
            }
        }
    }

    /**
     * What a stream listens to.
     *
     * @param caseKey    the punishment identifier
     * @param audience   {@link LiveAudience#PLAYER} for a player stream, {@link LiveAudience#STAFF} for a staff stream
     * @param sessionKey the player session's token hash; {@code null} for staff
     */
    public record Subscription(String caseKey, LiveAudience audience, @Nullable String sessionKey) {
    }

    /** No stream may be opened: the replica holds its maximum. */
    public static final class CapacityExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CapacityExceeded() {
            super("the live stream capacity of this replica is reached");
        }
    }

    /** An open stream; closing it is idempotent. */
    public static final class Stream {

        private final LiveHub hub;
        private final Entry entry;

        private Stream(LiveHub hub, Entry entry) {
            this.hub = hub;
            this.entry = entry;
        }

        /** Ends the stream without a frame, for a client that went away. */
        public void close() {
            this.hub.close(this.entry);
        }
    }

    private static final class Entry {

        final Subscription subscription;
        final Consumer<LiveFrame> out;
        final Runnable end;
        LiveScheduler.Handle keepAlive;
        LiveScheduler.Handle lifetime;
        boolean open = true;

        Entry(Subscription subscription, Consumer<LiveFrame> out, Runnable end) {
            this.subscription = subscription;
            this.out = out;
            this.end = end;
        }
    }

    private final LiveSequence sequence;
    private final LiveSessions sessions;
    private final LiveScheduler scheduler;
    private final Limits limits;
    private final Map<String, Set<Entry>> byCase = new HashMap<>();
    private final Set<Entry> all = new LinkedHashSet<>();

    /** Subscribes to the transport: every signal of every replica reaches this hub's streams. */
    public LiveHub(ChangeSignalTransport transport, LiveSequence sequence, LiveSessions sessions, LiveScheduler scheduler, Limits limits) {
        this.sequence = sequence;
        this.sessions = sessions;
        this.scheduler = scheduler;
        this.limits = limits;
        transport.subscribe(this::onSignal);
    }

    /**
     * Opens a stream and sends its first frames to {@code out}.
     *
     * @param lastEventId the {@code Last-Event-ID} header, or {@code null}; a malformed value counts as absent
     * @param out         receives the frames in order, on the thread that raised them
     * @param end         runs once when the stream is over
     * @throws CapacityExceeded when the replica holds {@link Limits#maxStreams()} streams (a session's older stream does not count)
     */
    public synchronized Stream open(Subscription subscription, @Nullable String lastEventId, Consumer<LiveFrame> out, Runnable end) {
        List<Entry> replaced = replacedBy(subscription);
        if (this.all.size() - replaced.size() >= this.limits.maxStreams()) {
            throw new CapacityExceeded();
        }
        long current = this.sequence.current(subscription.caseKey(), subscription.audience());
        for (Entry previous : replaced) {
            finish(previous, new LiveFrame.Replaced());
        }
        Entry entry = new Entry(subscription, out, end);
        this.all.add(entry);
        this.byCase.computeIfAbsent(subscription.caseKey(), key -> new LinkedHashSet<>()).add(entry);
        emit(entry, new LiveFrame.Retry(RETRY));
        emit(entry, new LiveFrame.Ready(current));
        Long last = parse(lastEventId);
        if (last != null && last < current) {
            emit(entry, new LiveFrame.Changed(current));
        }
        if (entry.open) {
            entry.lifetime = this.scheduler.schedule(this.limits.maxLifetime(), () -> close(entry));
            scheduleKeepAlive(entry);
        }
        return new Stream(this, entry);
    }

    /** The streams open on this replica right now. */
    public synchronized int openStreams() {
        return this.all.size();
    }

    /** Delivers a signal from any replica to the streams of its case. */
    synchronized void onSignal(ChangeSignal signal) {
        Set<Entry> entries = this.byCase.get(signal.caseKey());
        if (entries == null) {
            return;
        }
        for (Entry entry : List.copyOf(entries)) {
            if (signal.kind() == ChangeSignal.Kind.SESSIONS_ENDED) {
                if (entry.subscription.sessionKey() != null) {
                    finish(entry, new LiveFrame.Expired());
                }
            } else if (signal.audience() == LiveAudience.PLAYER || entry.subscription.audience() == LiveAudience.STAFF) {
                emit(entry, new LiveFrame.Changed(signal.seq()));
            }
        }
    }

    private void scheduleKeepAlive(Entry entry) {
        entry.keepAlive = this.scheduler.schedule(this.limits.keepAlive(), () -> keepAlive(entry));
    }

    /** The session is read outside the lock; a read that fails ends the stream, so a stream can never outlive its session. */
    private void keepAlive(Entry entry) {
        boolean alive = true;
        String sessionKey = entry.subscription.sessionKey();
        if (sessionKey != null) {
            try {
                alive = this.sessions.alive(sessionKey);
            } catch (RuntimeException e) {
                alive = false;
            }
        }
        synchronized (this) {
            if (!entry.open) {
                return;
            }
            if (!alive) {
                finish(entry, new LiveFrame.Expired());
                return;
            }
            emit(entry, new LiveFrame.KeepAlive());
            if (entry.open) {
                scheduleKeepAlive(entry);
            }
        }
    }

    private synchronized void close(Entry entry) {
        finish(entry, null);
    }

    /** The open streams of the same session that a new stream replaces; none for a staff stream. */
    private synchronized List<Entry> replacedBy(Subscription subscription) {
        List<Entry> replaced = new ArrayList<>();
        if (subscription.sessionKey() != null) {
            for (Entry entry : this.all) {
                if (subscription.sessionKey().equals(entry.subscription.sessionKey())) {
                    replaced.add(entry);
                }
            }
        }
        return replaced;
    }

    private void emit(Entry entry, LiveFrame frame) {
        if (!entry.open) {
            return;
        }
        try {
            entry.out.accept(frame);
        } catch (RuntimeException clientGone) {
            finish(entry, null);
        }
    }

    /** Ends a stream once: removes it, cancels its timers, sends an optional last frame and runs its end. */
    private void finish(Entry entry, @Nullable LiveFrame last) {
        if (!entry.open) {
            return;
        }
        entry.open = false;
        this.all.remove(entry);
        Set<Entry> entries = this.byCase.get(entry.subscription.caseKey());
        if (entries != null) {
            entries.remove(entry);
            if (entries.isEmpty()) {
                this.byCase.remove(entry.subscription.caseKey());
            }
        }
        cancel(entry.keepAlive);
        cancel(entry.lifetime);
        if (last != null) {
            try {
                entry.out.accept(last);
            } catch (RuntimeException ignored) {
                // the client is already gone; the stream still ends below
            }
        }
        try {
            entry.end.run();
        } catch (RuntimeException ignored) {
            // nothing to do about a failing end callback
        }
    }

    private static void cancel(@Nullable LiveScheduler.Handle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    private static @Nullable Long parse(@Nullable String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(lastEventId.strip());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
