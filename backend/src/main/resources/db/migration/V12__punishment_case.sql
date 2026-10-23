-- Punishment case (punishment-case capability): one case per punishment and an append-only event
-- log of everything that happens to it. The case holds the staff metadata (status, assignee,
-- priority, tags); case_event is the timeline.
--
-- The case is keyed by the punishment and lives exactly as long as it (ON DELETE CASCADE), like
-- evidence. case_event.type is plain text, not an ordinal: later changes and consumers add types
-- without a schema change. seq is the total order and the paging cursor; ties in occurred_at break
-- by seq. No actor name is stored: the display name is joined from staff_actor when read, so the
-- anonymize route erases it everywhere. payload is NULL only after a note redaction. dedupe_key
-- makes a lifecycle recording idempotent (a retry, the reconciler or a replay inserts nothing twice).
-- Enum-like columns (status, priority, origin, actor_trust, visibility) are validated by the
-- application, not by CHECK constraints (see .claude/rules/database.md).
CREATE TABLE punishment_case (
    punishment_identifier varchar(255) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    assignee_oid uuid,
    priority varchar(16) NOT NULL DEFAULT 'NORMAL',
    tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    CONSTRAINT punishment_case_pkey PRIMARY KEY (punishment_identifier),
    CONSTRAINT fk_punishment_case_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier) ON DELETE CASCADE
);

CREATE TABLE case_event (
    identifier uuid NOT NULL,
    seq bigint GENERATED ALWAYS AS IDENTITY,
    punishment_identifier varchar(255) NOT NULL,
    type varchar(48) NOT NULL,
    payload_version smallint NOT NULL DEFAULT 1,
    occurred_at bigint NOT NULL,
    origin varchar(16) NOT NULL,
    actor_trust varchar(16) NOT NULL,
    actor_oid uuid,
    recorded_by_oid uuid,
    actor_source uuid,
    subject_id varchar(64),
    visibility varchar(8) NOT NULL DEFAULT 'STAFF',
    payload jsonb,
    redacted_at bigint,
    redacted_by_oid uuid,
    dedupe_key varchar(128),
    CONSTRAINT case_event_pkey PRIMARY KEY (identifier),
    CONSTRAINT fk_case_event_case FOREIGN KEY (punishment_identifier)
        REFERENCES punishment_case (punishment_identifier) ON DELETE CASCADE
);
CREATE INDEX idx_case_event_case_seq ON case_event (punishment_identifier, seq);
CREATE INDEX idx_case_event_type ON case_event (type);
CREATE INDEX idx_case_event_actor ON case_event (actor_oid);
CREATE UNIQUE INDEX uq_case_event_dedupe ON case_event (dedupe_key);

-- BACKFILL: every existing punishment gets a case and the events its rows can tell. Backfilled
-- events have origin BACKFILLED. Actors without a recorded oid are UNVERIFIED; the ELO system's
-- punishments are SYSTEM. A time that is not recorded is approximate (payload flag). Cases of ended
-- punishments (lifted, or expired) start CLOSED, the others OPEN. Everything below is idempotent
-- (ON CONFLICT on the case key and the dedupe key): running it again inserts nothing.
-- 5b73dada-... is EloService.SYSTEM_ELO_SOURCE.
INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at)
SELECT p.identifier,
       CASE WHEN p.lifted
                 OR (jsonb_typeof(p.meta_data -> 'expiration_date') = 'number'
                     AND (p.meta_data ->> 'expiration_date')::numeric < (extract(epoch FROM clock_timestamp()) * 1000))
                THEN 'CLOSED' ELSE 'OPEN' END,
       'NORMAL', '[]'::jsonb, 0,
       COALESCE(p.created_at, 0),
       (extract(epoch FROM clock_timestamp()) * 1000)::bigint
FROM punishments p
ON CONFLICT (punishment_identifier) DO NOTHING;

WITH pm AS (
    SELECT p.identifier, p.source, p.recorded_by_oid, p.actor_oid, p.section_key, p.step_index, p.step_count,
           p.elo_cost, p.permanent, p.lifted, p.template_identifier,
           COALESCE(p.created_at, 0) AS created_at,
           CASE WHEN jsonb_typeof(p.meta_data -> 'expiration_date') = 'number'
                THEN (p.meta_data ->> 'expiration_date')::numeric::bigint END AS expires_at,
           CASE WHEN jsonb_typeof(p.meta_data -> 'update_date') = 'number'
                THEN (p.meta_data ->> 'update_date')::numeric::bigint END AS updated_at,
           CASE WHEN p.meta_data ->> 'revokedBy' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                THEN (p.meta_data ->> 'revokedBy')::uuid END AS ended_by,
           CASE WHEN p.meta_data ->> 'recordedByOid' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                THEN (p.meta_data ->> 'recordedByOid')::uuid END AS ended_recorded_by,
           (SELECT a.identifier FROM appeals a
             WHERE a.punishment_identifier = p.identifier AND a.status = 3
             ORDER BY a.updated_at DESC LIMIT 1) AS lifting_appeal,
           (SELECT max(a.updated_at) FROM appeals a WHERE a.punishment_identifier = p.identifier) AS last_appeal_at,
           (p.source = '5b73dada-07d8-3cb5-876e-92575000de68'::uuid) AS by_system
    FROM punishments p
),
ev AS (
    -- punishment.applied
    SELECT pm.identifier AS pid, 'punishment.applied' AS type, pm.created_at AS occurred_at,
           pm.source AS source, pm.recorded_by_oid AS recorded_by, pm.actor_oid AS actor_oid, pm.by_system AS by_system,
           NULL::varchar AS subject_id, 'applied:' || pm.identifier AS dedupe_key,
           jsonb_strip_nulls(jsonb_build_object(
               'section', pm.section_key, 'stepIndex', pm.step_index, 'stepCount', pm.step_count,
               'eloCost', pm.elo_cost, 'permanent', pm.permanent, 'escalated', pm.by_system,
               'templateId', pm.template_identifier, 'expiresAt', pm.expires_at)) AS payload,
           0 AS sort
    FROM pm
    UNION ALL
    -- appeal.filed (a filing records no actor)
    SELECT a.punishment_identifier, 'appeal.filed', a.created_at,
           NULL::uuid, NULL::uuid, NULL::uuid, false,
           a.identifier::varchar, 'appeal.filed:' || a.identifier,
           jsonb_build_object('appealId', a.identifier, 'eligible', a.status IS DISTINCT FROM 6),
           1
    FROM appeals a
    WHERE a.punishment_identifier IS NOT NULL
    UNION ALL
    -- appeal.decided (granted full lift, granted duration reduction, denied)
    SELECT a.punishment_identifier, 'appeal.decided', a.updated_at,
           a.decided_by, a.recorded_by_oid, NULL::uuid, false,
           a.identifier::varchar, 'appeal.decided:' || a.identifier,
           jsonb_build_object('appealId', a.identifier,
               'decision', CASE a.status WHEN 3 THEN 'GRANTED_FULL_LIFT' WHEN 4 THEN 'GRANTED_DURATION_REDUCTION' ELSE 'DENIED' END),
           2
    FROM appeals a
    WHERE a.punishment_identifier IS NOT NULL AND a.status IN (3, 4, 5)
    UNION ALL
    -- evidence.attached (the audit's ATTACHED line, action 0, has the exact time)
    SELECT ei.punishment_identifier, 'evidence.attached',
           COALESCE((SELECT min(au.occurred_at) FROM evidence_audit au
                      WHERE au.evidence_identifier = ei.identifier AND au.action = 0), ei.created_at),
           ei.created_by, ei.recorded_by_oid, ei.actor_oid, false,
           ei.identifier::varchar, 'evidence.attached:' || ei.identifier,
           jsonb_build_object('evidenceId', ei.identifier,
               'kind', CASE ei.kind WHEN 0 THEN 'ANTICHEAT' WHEN 1 THEN 'REPLAY' WHEN 2 THEN 'CHAT_LOG'
                                    WHEN 3 THEN 'LINK' ELSE 'FILE' END,
               'sourceSystem', ei.source_system),
           3
    FROM evidence_items ei
    WHERE ei.punishment_identifier IS NOT NULL
    UNION ALL
    -- evidence.revoked / evidence.lifted (the status itself is kept; who changed it is not)
    SELECT ei.punishment_identifier,
           CASE ei.status WHEN 1 THEN 'evidence.revoked' ELSE 'evidence.lifted' END,
           COALESCE(ei.status_changed_at, ei.created_at),
           NULL::uuid, NULL::uuid, NULL::uuid, false,
           ei.identifier::varchar,
           CASE ei.status WHEN 1 THEN 'evidence.revoked:' ELSE 'evidence.lifted:' END || ei.identifier,
           jsonb_strip_nulls(jsonb_build_object('evidenceId', ei.identifier, 'appealId', ei.lifted_by_appeal,
               'approximate', ei.status_changed_at IS NULL)),
           4
    FROM evidence_items ei
    WHERE ei.punishment_identifier IS NOT NULL AND ei.status IN (1, 2)
    UNION ALL
    -- punishment.lifted (a granted full-lift appeal) / punishment.revoked
    SELECT pm.identifier,
           CASE WHEN pm.lifting_appeal IS NOT NULL THEN 'punishment.lifted' ELSE 'punishment.revoked' END,
           COALESCE(pm.updated_at, pm.last_appeal_at, pm.created_at),
           pm.ended_by, pm.ended_recorded_by, NULL::uuid, false,
           CASE WHEN pm.lifting_appeal IS NOT NULL THEN pm.lifting_appeal::varchar END,
           CASE WHEN pm.lifting_appeal IS NOT NULL THEN 'lifted:' ELSE 'revoked:' END || pm.identifier,
           jsonb_strip_nulls(jsonb_build_object('appealId', pm.lifting_appeal, 'approximate', pm.updated_at IS NULL)),
           5
    FROM pm
    WHERE pm.lifted
    UNION ALL
    -- punishment.expired (the sweeper's event, at the expiry time)
    SELECT pm.identifier, 'punishment.expired', pm.expires_at,
           NULL::uuid, NULL::uuid, NULL::uuid, true,
           NULL::varchar, 'expired:' || pm.identifier,
           jsonb_build_object('expiredAt', pm.expires_at),
           6
    FROM pm
    WHERE NOT pm.lifted AND pm.expires_at IS NOT NULL
      AND pm.expires_at < (extract(epoch FROM clock_timestamp()) * 1000)
)
INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                        actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key)
SELECT gen_random_uuid(), ev.pid, ev.type, 1, ev.occurred_at, 'BACKFILLED',
       CASE WHEN ev.by_system THEN 'SYSTEM'
            WHEN ev.actor_oid IS NOT NULL OR (ev.recorded_by IS NOT NULL AND (ev.source IS NULL OR ev.source = ev.recorded_by))
                THEN 'VERIFIED'
            ELSE 'UNVERIFIED' END,
       CASE WHEN ev.by_system THEN NULL
            WHEN ev.actor_oid IS NOT NULL THEN ev.actor_oid
            WHEN ev.recorded_by IS NOT NULL AND (ev.source IS NULL OR ev.source = ev.recorded_by) THEN ev.recorded_by END,
       CASE WHEN ev.by_system THEN NULL ELSE ev.recorded_by END,
       CASE WHEN ev.by_system THEN NULL
            WHEN ev.actor_oid IS NULL AND NOT (ev.recorded_by IS NOT NULL AND (ev.source IS NULL OR ev.source = ev.recorded_by))
                THEN ev.source END,
       ev.subject_id, 'STAFF', ev.payload, ev.dedupe_key
FROM ev
ORDER BY ev.occurred_at, ev.sort
ON CONFLICT (dedupe_key) DO NOTHING;
