package net.onelitefeather.blackhole.discord.metrics;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The bot's counters. They carry no label and no value that could identify a player, a ticket or a message; they only
 * say how often something happened. Each bot instance and each test owns one.
 */
public final class Metrics {

    /** The counters; {@link #metricName()} is the exported name. */
    public enum Counter {
        RELAYS("relays_total"),
        INGESTS("ingests_total"),
        CATCH_UPS("catch_ups_total"),
        INCOMPLETE_TRANSCRIPTS("incomplete_transcripts_total"),
        TICKETS_OPENED("tickets_opened_total"),
        TICKETS_ARCHIVED("tickets_archived_total"),
        TICKETS_DELETED("tickets_deleted_total"),
        FREEZES("freezes_total"),
        BACKEND_UNAVAILABLE("backend_unavailable_total"),
        DISCORD_ERRORS("discord_errors_total"),
        BROKER_HINTS("broker_hints_total"),
        LEAK_CANDIDATES_DROPPED("leak_candidates_dropped_total"),
        LEAK_MESSAGES_REMOVED("leak_messages_removed_total"),
        LEAK_CHECKS("leak_checks_total"),
        AUDIT_POSTED("audit_posted_total"),
        AUDIT_RETRIED("audit_retried_total"),
        AUDIT_REJECTED("audit_rejected_total"),
        AUDIT_DROPPED("audit_dropped_total");

        private final String metricName;

        Counter(String metricName) {
            this.metricName = metricName;
        }

        public String metricName() {
            return "blackhole_discord_" + this.metricName;
        }
    }

    private final Map<Counter, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(@NotNull Counter counter) {
        this.counters.computeIfAbsent(counter, ignored -> new LongAdder()).increment();
    }

    public long value(@NotNull Counter counter) {
        LongAdder adder = this.counters.get(counter);
        return adder == null ? 0 : adder.sum();
    }

    /** @return the counters in the Prometheus text format */
    public @NotNull String render() {
        Map<String, Long> sorted = new TreeMap<>();
        for (Counter counter : Counter.values()) {
            sorted.put(counter.metricName(), value(counter));
        }
        StringBuilder text = new StringBuilder();
        sorted.forEach((name, value) -> text.append("# TYPE ").append(name).append(" counter\n").append(name).append(' ').append(value).append('\n'));
        return text.toString();
    }
}
