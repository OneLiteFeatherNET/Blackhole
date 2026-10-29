package net.onelitefeather.blackhole.backend.analytics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One test per view: seed a few base rows, refresh concurrently, and check the aggregate the view returns. */
class AnalyticsViewRefreshTest extends AnalyticsDatabaseTest {

    private static final String TEMPLATE_A = "00000000-0000-0000-0000-0000000000a1";

    @Test
    void punishmentsDailyCountsByKindTemplateAndWhetherTheEloEngineApplied() {
        insertTemplate(TEMPLATE_A, "spam", "Spam");
        insertPunishment("p1", "PUNISHMENT", false, SYSTEM_SOURCE, TEMPLATE_A, DAY);
        insertPunishment("p2", "PUNISHMENT", false, SYSTEM_SOURCE, TEMPLATE_A, DAY + HOUR);
        insertPunishment("p3", "PUNISHMENT", false, STAFF_SOURCE, TEMPLATE_A, DAY);
        insertPunishment("p4", "WARN", false, STAFF_SOURCE, null, DAY);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT kind, template_key, automatic, punishment_count FROM analytics.punishments_daily "
                + "ORDER BY kind, automatic");

        assertEquals(List.of(
                List.of("PUNISHMENT", "spam", false, 1L),
                List.of("PUNISHMENT", "spam", true, 2L),
                List.of("WARN", "", false, 1L)), rows);
    }

    @Test
    void reportsDailyCountsByCategoryAndStatusWithTheMedianOfResolvedReports() {
        insertReport(1, 2, OWNER_A, DAY, DAY + 60_000L);
        insertReport(1, 2, OWNER_B, DAY, DAY + 180_000L);
        insertReport(1, 0, OWNER_B, DAY, DAY + 5_000L);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT category, status, report_count, median_resolve_seconds "
                + "FROM analytics.reports_daily ORDER BY status");

        assertEquals(List.of(
                Arrays.asList("CHEATING", "ACTIONED", 2L, 120.0),
                Arrays.asList("CHEATING", "OPEN", 1L, null)), rows, "an open report has no resolve time");
    }

    @Test
    void reportReportersCountsTheReportsAndTheUpheldOnesPerOwnerHash() {
        insertReport(0, 2, OWNER_A, DAY, DAY);
        insertReport(0, 3, OWNER_A, DAY + 2 * 24 * HOUR, DAY + 2 * 24 * HOUR);
        insertReport(0, 3, OWNER_B, DAY, DAY);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT owner_hash, report_count, upheld_count, last_report_day "
                + "FROM analytics.report_reporters ORDER BY report_count DESC");

        assertEquals(List.of(
                List.of(OWNER_A, 2L, 1L, java.sql.Date.valueOf("2026-10-03")),
                List.of(OWNER_B, 1L, 0L, java.sql.Date.valueOf("2026-10-01"))), rows);
    }

    @Test
    void appealsDailyCountsResolvedAppealsByOutcomeAndAutomaticFlagWithTheMedianDecisionTime() {
        insertPunishment("pa", "PUNISHMENT", false, SYSTEM_SOURCE, null, DAY);
        insertPunishment("pb", "PUNISHMENT", false, STAFF_SOURCE, null, DAY);
        insertPunishment("pc", "PUNISHMENT", false, STAFF_SOURCE, null, DAY);
        insertAppeal(5, "pa", DAY, DAY + 2 * HOUR);
        insertAppeal(3, "pb", DAY, DAY + 4 * HOUR);
        insertAppeal(1, "pc", DAY, DAY + 9 * HOUR);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT outcome, automatic, appeal_count, median_decision_hours "
                + "FROM analytics.appeals_daily ORDER BY outcome");

        assertEquals(List.of(
                List.of("DENIED", true, 1L, 2.0),
                List.of("GRANTED_FULL_LIFT", false, 1L, 4.0)), rows, "open appeals (SUBMITTED, ...) are not decisions");
    }

    @Test
    void eloDistributionCountsOwnersPerSectionAndFiftyPointBucket() {
        insertEloScore("owner-1", "network", 340);
        insertEloScore("owner-2", "network", 350);
        insertEloScore("owner-3", "network", 399);
        insertEloScore("owner-4", "network", -10);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT section_key, score_bucket, owner_count FROM analytics.elo_distribution "
                + "ORDER BY score_bucket");

        assertEquals(List.of(
                List.of("network", -50, 1L),
                List.of("network", 300, 1L),
                List.of("network", 350, 2L)), rows, "a bucket of 350 holds the scores 350 to 399");
    }

    @Test
    void clientRuleHitsDailyCountsRuleActionFlagsAndServer() {
        insertClientRule("brand-x");
        insertRuleHit("brand-x", OWNER_A, DAY, "NOTIFY", false, false, "lobby");
        insertRuleHit("brand-x", OWNER_B, DAY + HOUR, "NOTIFY", false, false, "lobby");
        insertRuleHit("brand-x", OWNER_B, DAY, "DENY", true, false, null);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT rule_key, action, enforced, server_name, hit_count "
                + "FROM analytics.client_rule_hits_daily ORDER BY action");

        assertEquals(List.of(
                List.of("brand-x", "DENY", true, "", 1L),
                List.of("brand-x", "NOTIFY", false, "lobby", 2L)), rows);
    }

    @Test
    void staffActivityDailyCountsEventsAndDistinctActingStaffPerType() {
        insertPunishment("pe", "PUNISHMENT", false, STAFF_SOURCE, null, DAY);
        insertCase("pe");
        insertCaseEvent("pe", "APPENDED", DAY, staffOid(1));
        insertCaseEvent("pe", "APPENDED", DAY + 1000, staffOid(1));
        insertCaseEvent("pe", "APPENDED", DAY + 2000, staffOid(2));
        insertCaseEvent("pe", "VIEWED", DAY + 3000, null);
        refreshAllViews();

        List<List<Object>> rows = this.db.rows("SELECT event_type, event_count, distinct_actors FROM analytics.staff_activity_daily "
                + "ORDER BY event_type");

        assertEquals(List.of(
                List.of("APPENDED", 3L, 2L),
                List.of("VIEWED", 1L, 0L)), rows);
    }
}
