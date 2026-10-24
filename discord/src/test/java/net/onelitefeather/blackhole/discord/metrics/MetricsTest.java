package net.onelitefeather.blackhole.discord.metrics;

import net.onelitefeather.blackhole.discord.gateway.HealthServer;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {

    @Test
    void counters_start_at_zero_and_count_up() {
        Metrics metrics = new Metrics();

        metrics.increment(Counter.RELAYS);
        metrics.increment(Counter.RELAYS);

        assertEquals(2, metrics.value(Counter.RELAYS), "relays");
        assertEquals(0, metrics.value(Counter.INGESTS), "untouched counter");
    }

    @Test
    void the_prometheus_text_lists_every_counter_with_its_value_and_no_label() {
        Metrics metrics = new Metrics();
        metrics.increment(Counter.LEAK_CANDIDATES_DROPPED);

        String text = metrics.render();

        assertTrue(text.contains("blackhole_discord_leak_candidates_dropped_total 1\n"), text);
        assertTrue(text.contains("blackhole_discord_relays_total 0\n"), "zero counters are listed too");
        for (Counter counter : Counter.values()) {
            assertTrue(text.contains("# TYPE " + counter.metricName() + " counter"), counter + " is exported");
        }
        assertFalse(text.contains("{"), "no labels, so nothing identifying can ride along");
    }

    @Test
    void the_status_is_healthy_only_when_connected_and_without_permission_problems() {
        assertTrue(new HealthServer.Status(true, List.of()).healthy(), "connected and clean");
        assertFalse(new HealthServer.Status(false, List.of()).healthy(), "not connected");
        assertFalse(new HealthServer.Status(true, List.of("ticket category: missing MANAGE_CHANNEL")).healthy(), "a missing permission");
    }
}
