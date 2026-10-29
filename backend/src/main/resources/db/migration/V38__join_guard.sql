-- Join guard and area bans (openspec add-join-guard, design decisions 1, 3 and 12). Additive only: three new tables,
-- no existing table changes. Times are epoch milliseconds, as in player_sighting. Nothing here is written per login or
-- per heartbeat: the presence hash and the counters live in Dragonfly.

-- One row per ASN or country. An ASN or a country code is not personal data, so there is no retention sweeper.
-- The partial unique index allows one active ban per area; lifted and expired rows stay as history.
CREATE TABLE area_ban (
    id uuid NOT NULL,
    kind varchar(8) NOT NULL,
    value varchar(10) NOT NULL,
    reason varchar(200) NOT NULL,
    source varchar(16) NOT NULL,
    actor_oid varchar(64),
    created_at bigint NOT NULL,
    expires_at bigint,
    lifted_at bigint,
    lift_reason varchar(200),
    lifted_by_oid varchar(64),
    CONSTRAINT pk_area_ban PRIMARY KEY (id),
    CONSTRAINT ck_area_ban_kind CHECK (kind IN ('ASN', 'COUNTRY')),
    CONSTRAINT ck_area_ban_source CHECK (source IN ('MANUAL', 'AUTO_BURST'))
);
CREATE UNIQUE INDEX ux_area_ban_active ON area_ban (kind, value) WHERE lifted_at IS NULL;

-- The single guard row. The effective level is the higher of the manual and the automatic level. It is stored so that
-- one conditional UPDATE can move the row from a known state; no transaction is needed.
CREATE TABLE join_guard_state (
    id smallint NOT NULL,
    manual_level varchar(8) NOT NULL,
    automatic_level varchar(8) NOT NULL,
    effective_level varchar(8) NOT NULL,
    updated_at bigint NOT NULL,
    CONSTRAINT pk_join_guard_state PRIMARY KEY (id),
    CONSTRAINT ck_join_guard_state_single CHECK (id = 1),
    CONSTRAINT ck_join_guard_state_manual CHECK (manual_level IN ('OFF', 'STRICT', 'LOCKDOWN')),
    CONSTRAINT ck_join_guard_state_automatic CHECK (automatic_level IN ('OFF', 'STRICT', 'LOCKDOWN')),
    CONSTRAINT ck_join_guard_state_effective CHECK (effective_level IN ('OFF', 'STRICT', 'LOCKDOWN'))
);
INSERT INTO join_guard_state (id, manual_level, automatic_level, effective_level, updated_at)
VALUES (1, 'OFF', 'OFF', 'OFF', 0);

-- The level history. actor_oid is set for panel and in-game actions only; it is NULL for the unattended evaluator.
CREATE TABLE join_guard_change (
    id bigserial NOT NULL,
    changed_at bigint NOT NULL,
    previous_level varchar(8) NOT NULL,
    new_level varchar(8) NOT NULL,
    source varchar(16) NOT NULL,
    actor_oid varchar(64),
    CONSTRAINT pk_join_guard_change PRIMARY KEY (id),
    CONSTRAINT ck_join_guard_change_source CHECK (source IN ('MANUAL', 'UNATTENDED', 'CLEARED'))
);
CREATE INDEX ix_join_guard_change_changed ON join_guard_change (changed_at);
