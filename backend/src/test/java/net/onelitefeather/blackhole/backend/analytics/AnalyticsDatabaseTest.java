package net.onelitefeather.blackhole.backend.analytics;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.time.Instant;

/**
 * Base for the analytics tests that need PostgreSQL: each test gets a database of its own, migrated by the real Flyway files, and drops
 * it afterwards. The views and the reporting role are per database or cluster-wide, so nothing one test writes reaches another.
 */
@Tag("integration")
abstract class AnalyticsDatabaseTest extends PostgresIntegrationTest {

    /** 2026-10-01 00:00 UTC, the day most seeded rows fall on. */
    protected static final long DAY = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
    protected static final long HOUR = 3_600_000L;
    protected static final String OWNER_A = "a".repeat(128);
    protected static final String OWNER_B = "b".repeat(128);
    protected static final String SYSTEM_SOURCE = "5b73dada-07d8-3cb5-876e-92575000de68";
    protected static final String STAFF_SOURCE = "00000000-0000-0000-0000-00000000005a";

    protected AnalyticsDatabase db;

    @BeforeEach
    void givenAFreshAnalyticsDatabase() {
        this.db = AnalyticsDatabase.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterEach
    void dropTheDatabase() {
        if (this.db != null) {
            this.db.drop(POSTGRES.getJdbcUrl());
        }
    }

    /** Refreshes every view concurrently, the way the scheduled job does once the views are populated. */
    protected void refreshAllViews() {
        for (String view : AnalyticsViews.ALL) {
            this.db.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY analytics." + view);
        }
    }

    protected void insertPunishment(String identifier, String kind, boolean permanent, String source, String templateIdentifier,
                                    Long createdAt) {
        this.db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, "
                        + "meta_data, kind, template_identifier) VALUES (?, CAST(? AS uuid), ?, 'network', ?, false, ?, ?, '{}'::jsonb, ?, "
                        + "CAST(? AS uuid))",
                identifier, source, OWNER_A, permanent, createdAt, identifier.substring(0, Math.min(8, identifier.length())), kind,
                templateIdentifier);
    }

    protected void insertTemplate(String identifier, String templateKey, String reason) {
        this.db.execute("INSERT INTO punishment_templates (identifier, template_key, section_key, reason) "
                + "VALUES (CAST(? AS uuid), ?, 'network', ?)", identifier, templateKey, reason);
    }

    protected void insertReport(Integer category, Integer status, String reporterHash, long createdAt, long updatedAt) {
        this.db.execute("INSERT INTO reports (identifier, category, status, reporter_hash, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)", category, status, reporterHash, createdAt, updatedAt);
    }

    protected void insertAppeal(int status, String punishmentIdentifier, long createdAt, long updatedAt) {
        this.db.execute("INSERT INTO appeals (identifier, status, appellant_hash, punishment_identifier, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)", status, OWNER_B, punishmentIdentifier, createdAt, updatedAt);
    }

    protected void insertEloScore(String owner, String sectionKey, int score) {
        this.db.execute("INSERT INTO elo_scores (owner, section_key, score, updated_at) VALUES (?, ?, ?, ?)", owner, sectionKey, score, DAY);
    }

    protected void insertClientRule(String ruleKey) {
        this.db.execute("INSERT INTO client_rule (rule_key, display_name, kind, created_at, updated_at) VALUES (?, ?, 'BRAND', ?, ?)",
                ruleKey, ruleKey, DAY, DAY);
    }

    protected void insertRuleHit(String ruleKey, String ownerHash, long at, String action, boolean enforced, boolean bypassed,
                                 String serverName) {
        this.db.execute("INSERT INTO client_rule_hit (rule_key, owner_hash, at, action_taken, enforced, bypassed, server_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", ruleKey, ownerHash, at, action, enforced, bypassed, serverName);
    }

    /** A punishment case row, which every case event needs. */
    protected void insertCase(String punishmentIdentifier) {
        this.db.execute("INSERT INTO punishment_case (punishment_identifier, created_at, updated_at) VALUES (?, ?, ?)",
                punishmentIdentifier, DAY, DAY);
    }

    protected void insertCaseEvent(String punishmentIdentifier, String type, long occurredAt, String actorOid) {
        this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, occurred_at, origin, actor_trust, actor_oid) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, 'LIVE', 'VERIFIED', CAST(? AS uuid))",
                punishmentIdentifier, type, occurredAt, actorOid);
    }

    protected static String staffOid(int n) {
        return String.format("00000000-0000-0000-0000-%012d", n);
    }
}
