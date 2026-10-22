package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A short-lived, in-memory buffer of recent public chat, kept per owner hash (never per UUID or
 * name) so staff can attach it as evidence when they punish a player. It holds at most
 * {@code lines} lines per player, each for at most {@code max-age}, lives in RAM only, is never
 * logged or written anywhere and is dropped with {@link #clear(String)} when the player leaves.
 * When capture is disabled (the default) it stores nothing.
 *
 * <p>{@link #record} runs on the proxy's event thread and only touches one player's small, bounded
 * deque. Time comes from the injected {@link Clock}.
 */
public final class ChatBuffer {

    /** The longest text kept per line; longer messages are cut so memory use stays bounded. */
    public static final int MAX_TEXT_LENGTH = 256;

    /**
     * One buffered public chat line.
     *
     * @param sequence  a number that grows with every recorded line, to order lines sent in the same millisecond
     * @param timestamp when the line was sent, epoch millis
     * @param owner     the sender's owner hash
     * @param server    the backend server the line was sent on
     * @param text      the line, cut to {@link #MAX_TEXT_LENGTH}
     */
    public record Line(long sequence, long timestamp, String owner, String server, String text) {
    }

    private final Clock clock;
    private final boolean enabled;
    private final int maxLines;
    private final Duration maxAge;
    private final Map<String, Deque<Line>> byOwner = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public ChatBuffer(@NotNull Clock clock, @NotNull ChatCaptureConfig config) {
        this.clock = clock;
        this.enabled = config.isEnabled();
        this.maxLines = config.getLines();
        this.maxAge = config.getMaxAge();
    }

    /**
     * @return whether lines are being buffered
     */
    public boolean enabled() {
        return this.enabled;
    }

    /**
     * Buffers one public chat line of the owner; does nothing when capture is disabled. Lines past
     * the per-player limit and lines older than the max age are dropped on the way.
     */
    public void record(@NotNull String owner, @NotNull String server, @NotNull String text) {
        if (!this.enabled) {
            return;
        }
        long now = this.clock.millis();
        String kept = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
        Line line = new Line(this.sequence.incrementAndGet(), now, owner, server, kept);
        // compute() makes adding atomic with evictExpired() forgetting an emptied player.
        this.byOwner.compute(owner, (key, existing) -> {
            Deque<Line> lines = existing == null ? new ArrayDeque<>() : existing;
            synchronized (lines) {
                dropExpired(lines, now);
                lines.addLast(line);
                while (lines.size() > this.maxLines) {
                    lines.removeFirst();
                }
            }
            return lines;
        });
    }

    /**
     * @return the owner's lines that are still within the max age, oldest first
     */
    public @NotNull List<Line> linesOf(@NotNull String owner) {
        Deque<Line> lines = this.byOwner.get(owner);
        if (lines == null) {
            return List.of();
        }
        long now = this.clock.millis();
        synchronized (lines) {
            return lines.stream().filter(line -> !expired(line, now)).toList();
        }
    }

    /**
     * @return the lines of every owner but {@code excluded} that were sent on the server and are still
     * within the max age, oldest first
     */
    public @NotNull List<Line> linesOnServerExcluding(@NotNull String server, @NotNull String excluded) {
        long now = this.clock.millis();
        List<Line> found = new ArrayList<>();
        this.byOwner.forEach((owner, lines) -> {
            if (owner.equals(excluded)) {
                return;
            }
            synchronized (lines) {
                for (Line line : lines) {
                    if (line.server().equals(server) && !expired(line, now)) {
                        found.add(line);
                    }
                }
            }
        });
        found.sort(Comparator.comparingLong(Line::sequence));
        return found;
    }

    /**
     * Drops everything buffered for the owner; called when they disconnect.
     */
    public void clear(@NotNull String owner) {
        this.byOwner.remove(owner);
    }

    /**
     * Drops expired lines of every player and forgets players with none left, so a player who stopped
     * chatting does not keep old lines until they leave. Meant to run periodically, off the event thread.
     */
    public void evictExpired() {
        long now = this.clock.millis();
        for (String owner : this.byOwner.keySet()) {
            this.byOwner.computeIfPresent(owner, (key, lines) -> {
                synchronized (lines) {
                    dropExpired(lines, now);
                    return lines.isEmpty() ? null : lines;
                }
            });
        }
    }

    /**
     * @return how many players currently have buffered lines
     */
    public int trackedPlayers() {
        return this.byOwner.size();
    }

    private void dropExpired(Deque<Line> lines, long now) {
        while (!lines.isEmpty() && expired(lines.peekFirst(), now)) {
            lines.removeFirst();
        }
    }

    private boolean expired(Line line, long now) {
        return now - line.timestamp() > this.maxAge.toMillis();
    }
}
