package net.onelitefeather.blackhole.backend.analytics;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The views decode ordinal enum codes in SQL. Each test stores every ordinal of the Java enum and checks that the view names it, so a
 * value added to the enum without a mapping fails here instead of showing as UNKNOWN on a dashboard.
 */
class EnumMappingTest extends AnalyticsDatabaseTest {

    @Test
    void everyReportStatusOrdinalIsNamedInReportsDaily() {
        for (ReportStatus status : ReportStatus.values()) {
            insertReport(0, status.ordinal(), OWNER_A, DAY, DAY + HOUR);
        }
        refreshAllViews();

        Set<String> named = distinct("SELECT DISTINCT status FROM analytics.reports_daily");

        assertEquals(names(ReportStatus.values()), named, "every ReportStatus ordinal needs a name in reports_daily");
        assertFalse(named.contains("UNKNOWN"));
    }

    @Test
    void everyReportCategoryOrdinalIsNamedInReportsDaily() {
        for (ReportCategory category : ReportCategory.values()) {
            insertReport(category.ordinal(), 0, OWNER_A, DAY, DAY);
        }
        refreshAllViews();

        Set<String> named = distinct("SELECT DISTINCT category FROM analytics.reports_daily");

        assertEquals(names(ReportCategory.values()), named, "every ReportCategory ordinal needs a name in reports_daily");
        assertFalse(named.contains("UNKNOWN"));
    }

    @Test
    void everyAppealStatusOrdinalIsClassifiedAsOpenOrResolvedAndResolvedOnesAreNamed() {
        Set<String> open = Set.of("SUBMITTED", "ELIGIBLE_PENDING_REVIEW", "IN_REVIEW");
        Set<String> resolved = Set.of("GRANTED_FULL_LIFT", "GRANTED_DURATION_REDUCTION", "DENIED", "INELIGIBLE");
        Set<String> all = names(AppealStatus.values());
        assertEquals(all, union(open, resolved), "a new AppealStatus must be classified in appeals_daily (open or resolved) first");

        for (AppealStatus status : AppealStatus.values()) {
            insertAppeal(status.ordinal(), null, DAY, DAY + HOUR);
        }
        refreshAllViews();

        assertEquals(resolved, distinct("SELECT DISTINCT outcome FROM analytics.appeals_daily"),
                "every resolved AppealStatus ordinal needs a name in appeals_daily");
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return Set.copyOf(java.util.stream.Stream.concat(a.stream(), b.stream()).toList());
    }

    private Set<String> distinct(String sql) {
        return this.db.column(sql).stream().map(String::valueOf).collect(Collectors.toSet());
    }
}
