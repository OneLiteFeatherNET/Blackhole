package net.onelitefeather.blackhole.backend.analytics;

import java.util.List;

/**
 * The materialized views of the {@code analytics} schema, in refresh order. Each one is created by V37 and must be listed here
 * exactly once; the names are interpolated into refresh statements, so they are constants and never input.
 */
public final class AnalyticsViews {

    /** The views read only base tables, so the order does not change a result. */
    public static final List<String> ALL = List.of(
            "punishments_daily",
            "reports_daily",
            "report_reporters",
            "appeals_daily",
            "elo_distribution",
            "client_rule_hits_daily",
            "staff_activity_daily");

    private AnalyticsViews() {
    }
}
