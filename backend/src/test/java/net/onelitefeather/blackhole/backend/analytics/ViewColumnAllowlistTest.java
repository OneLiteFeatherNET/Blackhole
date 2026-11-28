package net.onelitefeather.blackhole.backend.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The privacy rule of the views is checked by listing their columns. A column added later fails this test until the allowlist is
 * changed on purpose, in the same change, with a reason.
 */
class ViewColumnAllowlistTest extends AnalyticsDatabaseTest {

    private static final Map<String, List<String>> ALLOWLIST = Map.of(
            "punishments_daily", List.of("automatic", "day", "kind", "permanent", "punishment_count", "section_key", "template_key",
                    "template_reason"),
            "reports_daily", List.of("category", "day", "median_resolve_seconds", "report_count", "status"),
            "report_reporters", List.of("last_report_day", "owner_hash", "report_count", "upheld_count"),
            "appeals_daily", List.of("appeal_count", "automatic", "day", "median_decision_hours", "outcome"),
            "elo_distribution", List.of("owner_count", "score_bucket", "section_key"),
            "client_rule_hits_daily", List.of("action", "bypassed", "day", "enforced", "hit_count", "rule_key", "server_name"),
            "staff_activity_daily", List.of("day", "distinct_actors", "event_count", "event_type"));

    /** Names of personal data, or of anything a view must never hold, compared exactly. */
    private static final Set<String> FORBIDDEN = Set.of("ip", "ip_address", "uuid", "player_uuid", "name", "player_name", "meta_data",
            "actor_oid", "recorded_by_oid", "appellant_hash", "resolved_by", "decided_by", "source", "owner", "reported_hash");

    @Test
    void eachViewExposesExactlyItsAllowlistedColumns() {
        for (Map.Entry<String, List<String>> view : ALLOWLIST.entrySet()) {
            assertEquals(view.getValue(), columnsOf(view.getKey()), "the columns of " + view.getKey() + " must equal its allowlist");
        }
    }

    @Test
    void noViewHoldsAPersonalIdentifierOtherThanTheReporterOwnerHash() {
        for (String view : ALLOWLIST.keySet()) {
            for (String column : columnsOf(view)) {
                assertFalse(FORBIDDEN.contains(column), "view " + view + " must not expose " + column);
                assertFalse(column.startsWith("ban_id"), "view " + view + " must not expose a Ban ID");
                if (column.equals("owner_hash")) {
                    assertEquals("report_reporters", view, "only report_reporters may hold an owner hash");
                }
            }
        }
    }

    /** The column names of a materialized view in the database, in name order. */
    private List<String> columnsOf(String view) {
        return this.db.column("SELECT a.attname FROM pg_attribute a "
                        + "JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'analytics' AND c.relname = ? AND c.relkind = 'm' AND a.attnum > 0 AND NOT a.attisdropped "
                        + "ORDER BY a.attname", view).stream().map(String::valueOf).toList();
    }
}
