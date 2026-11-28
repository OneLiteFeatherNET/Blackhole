-- Analytics for Metabase (add-postgres-analytics): one schema of materialized views over the operational
-- tables. Each view is an aggregate with explicit columns: no IP address, no raw player UUID, no name, no
-- meta_data, no staff OID. The only owner value is the owner hash in report_reporters.
--
-- Ordinal enums are stored as their ordinal (no @Enumerated on the entity). The codes below follow the Java
-- enums; a new value must be appended both there and here, and EnumMappingTest fails until it is.
--   AppealStatus : 0 SUBMITTED, 1 ELIGIBLE_PENDING_REVIEW, 2 IN_REVIEW, 3 GRANTED_FULL_LIFT,
--                  4 GRANTED_DURATION_REDUCTION, 5 DENIED, 6 INELIGIBLE
--   ReportStatus : 0 OPEN, 1 UNDER_REVIEW, 2 ACTIONED, 3 DISMISSED
--   ReportCategory: 0 CHAT_ABUSE, 1 CHEATING, 2 GRIEFING, 3 OTHER
-- Timestamps are epoch milliseconds (bigint). Days are UTC.
-- The DDL is idempotent: the analytics schema belongs to the database, and the test container runs the migrations into
-- several schemas of one database. Production migrates the database once.
-- Nullable grouping columns are coalesced to '' so every unique index is complete.

CREATE SCHEMA IF NOT EXISTS analytics;

-- Cluster-wide group role, created once. Without a login and without a password; the login role that Metabase
-- uses is created by the operator (CNPG managed role, member of this role), never in a migration.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_reader') THEN
        CREATE ROLE analytics_reader NOLOGIN;
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS analytics.refresh_state (
    view_name    varchar(64) PRIMARY KEY,
    refreshed_at bigint NOT NULL
);

-- Punishments per UTC day, kind, section, template and permanence. The punishments.type column is not read:
-- the entity no longer maps it (V4 superseded it), so it is not a grouping key.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.punishments_daily AS
WITH base AS (
    SELECT (to_timestamp(p.created_at / 1000.0) AT TIME ZONE 'UTC')::date AS day,
           p.kind,
           COALESCE(p.section_key, '') AS section_key,
           COALESCE(t.template_key, '') AS template_key,
           COALESCE(t.reason, '') AS template_reason,
           p.permanent,
           COALESCE(p.source = '5b73dada-07d8-3cb5-876e-92575000de68'::uuid, false) AS automatic
    FROM punishments p
    LEFT JOIN punishment_templates t ON t.identifier = p.template_identifier
    WHERE p.created_at IS NOT NULL
)
SELECT day, kind, section_key, template_key, template_reason, permanent, automatic,
       count(*) AS punishment_count
FROM base
GROUP BY day, kind, section_key, template_key, template_reason, permanent, automatic
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_punishments_daily
    ON analytics.punishments_daily (day, kind, section_key, template_key, template_reason, permanent, automatic);

-- Reports per UTC day of creation, category and status. The median is over resolved reports only (ACTIONED or
-- DISMISSED): seconds from creation to the last change of the report.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.reports_daily AS
WITH base AS (
    SELECT (to_timestamp(r.created_at / 1000.0) AT TIME ZONE 'UTC')::date AS day,
           CASE r.category
               WHEN 0 THEN 'CHAT_ABUSE'
               WHEN 1 THEN 'CHEATING'
               WHEN 2 THEN 'GRIEFING'
               WHEN 3 THEN 'OTHER'
               ELSE 'UNKNOWN'
           END AS category,
           CASE r.status
               WHEN 0 THEN 'OPEN'
               WHEN 1 THEN 'UNDER_REVIEW'
               WHEN 2 THEN 'ACTIONED'
               WHEN 3 THEN 'DISMISSED'
               ELSE 'UNKNOWN'
           END AS status,
           r.status AS status_code,
           ((r.updated_at - r.created_at) / 1000.0)::double precision AS seconds_to_change
    FROM reports r
)
SELECT day, category, status,
       count(*) AS report_count,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds_to_change)
           FILTER (WHERE status_code IN (2, 3)) AS median_resolve_seconds
FROM base
GROUP BY day, category, status
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_reports_daily ON analytics.reports_daily (day, category, status);

-- One row per reporter owner hash. Upheld means ACTIONED (open question 1 in design.md). The owner hash is the only
-- owner value any view holds.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.report_reporters AS
SELECT COALESCE(r.reporter_hash, '') AS owner_hash,
       count(*) AS report_count,
       count(*) FILTER (WHERE r.status = 2) AS upheld_count,
       max((to_timestamp(r.created_at / 1000.0) AT TIME ZONE 'UTC')::date) AS last_report_day
FROM reports r
GROUP BY COALESCE(r.reporter_hash, '')
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_report_reporters ON analytics.report_reporters (owner_hash);

-- Resolved appeals per UTC day of decision, outcome and whether the appealed punishment was automatic. The decision
-- time is the last change of the appeal (appeals.updated_at), an approximation; exact times stay in case_event.
-- Resolved means one of GRANTED_FULL_LIFT, GRANTED_DURATION_REDUCTION, DENIED, INELIGIBLE.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.appeals_daily AS
WITH base AS (
    SELECT (to_timestamp(a.updated_at / 1000.0) AT TIME ZONE 'UTC')::date AS day,
           CASE a.status
               WHEN 0 THEN 'SUBMITTED'
               WHEN 1 THEN 'ELIGIBLE_PENDING_REVIEW'
               WHEN 2 THEN 'IN_REVIEW'
               WHEN 3 THEN 'GRANTED_FULL_LIFT'
               WHEN 4 THEN 'GRANTED_DURATION_REDUCTION'
               WHEN 5 THEN 'DENIED'
               WHEN 6 THEN 'INELIGIBLE'
               ELSE 'UNKNOWN'
           END AS outcome,
           COALESCE(p.source = '5b73dada-07d8-3cb5-876e-92575000de68'::uuid, false) AS automatic,
           ((a.updated_at - a.created_at) / 3600000.0)::double precision AS hours_to_decision
    FROM appeals a
    LEFT JOIN punishments p ON p.identifier = a.punishment_identifier
    WHERE a.status IN (3, 4, 5, 6)
)
SELECT day, outcome, automatic,
       count(*) AS appeal_count,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY hours_to_decision) AS median_decision_hours
FROM base
GROUP BY day, outcome, automatic
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_appeals_daily ON analytics.appeals_daily (day, outcome, automatic);

-- Owners per section and score bucket of 50 points (a bucket of 350 holds scores from 350 up to 399).
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.elo_distribution AS
SELECT s.section_key,
       (floor(s.score / 50.0) * 50)::integer AS score_bucket,
       count(*) AS owner_count
FROM elo_scores s
GROUP BY s.section_key, (floor(s.score / 50.0) * 50)::integer
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_elo_distribution ON analytics.elo_distribution (section_key, score_bucket);

-- Client rule hits per UTC day. The rule key already says what matched; no client trait is stored or shown.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.client_rule_hits_daily AS
WITH base AS (
    SELECT (to_timestamp(h.at / 1000.0) AT TIME ZONE 'UTC')::date AS day,
           h.rule_key,
           h.action_taken AS action,
           h.enforced,
           h.bypassed,
           COALESCE(h.server_name, '') AS server_name
    FROM client_rule_hit h
)
SELECT day, rule_key, action, enforced, bypassed, server_name, count(*) AS hit_count
FROM base
GROUP BY day, rule_key, action, enforced, bypassed, server_name
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_client_rule_hits_daily
    ON analytics.client_rule_hits_daily (day, rule_key, action, enforced, bypassed, server_name);

-- Case events per UTC day and event type: the count, and the number of distinct acting staff. No staff OID leaves
-- the view; the distinct count is a number.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.staff_activity_daily AS
WITH base AS (
    SELECT (to_timestamp(e.occurred_at / 1000.0) AT TIME ZONE 'UTC')::date AS day,
           e.type AS event_type,
           e.actor_oid
    FROM case_event e
)
SELECT day, event_type,
       count(*) AS event_count,
       count(DISTINCT actor_oid) AS distinct_actors
FROM base
GROUP BY day, event_type
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uq_analytics_staff_activity_daily ON analytics.staff_activity_daily (day, event_type);

-- The reporting role reads the analytics schema only: no base table, no public schema, no write.
GRANT USAGE ON SCHEMA analytics TO analytics_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO analytics_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO analytics_reader;
