-- A kick is a punishment row with kind KICK: it ends no later than it starts, never occupies an
-- active slot and is never mirrored to the proxies, but it counts as an earlier step of its
-- escalation group. Existing rows read as PUNISHMENT and existing template steps as PUNISH.
ALTER TABLE punishments ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'PUNISHMENT';
ALTER TABLE punishments ADD COLUMN kick_scope varchar(16);
ALTER TABLE punishments ADD COLUMN kick_target varchar(255);
ALTER TABLE punishments ADD CONSTRAINT ck_punishments_kind CHECK (kind IN ('PUNISHMENT', 'KICK'));
CREATE INDEX idx_punishments_kind_owner ON punishments (kind, owner);

-- A KICK step has no duration.
ALTER TABLE punishment_template_steps ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'PUNISH';
ALTER TABLE punishment_template_steps ALTER COLUMN duration DROP NOT NULL;

-- One audited admin action (a mass kick keeps one record, no per-player punishment).
CREATE TABLE kick_action (
    id uuid NOT NULL,
    scope varchar(16) NOT NULL,
    target varchar(255),
    reason varchar(200) NOT NULL,
    actor_oid uuid,
    created_at bigint NOT NULL,
    deadline_at bigint NOT NULL,
    CONSTRAINT pk_kick_action PRIMARY KEY (id)
);

-- What each proxy reported for a kicked subject. subject_id is a kick punishment or action id,
-- never a player UUID.
CREATE TABLE kick_delivery (
    subject_id uuid NOT NULL,
    proxy_id varchar(64) NOT NULL,
    found boolean NOT NULL,
    moved boolean NOT NULL,
    acked_at bigint NOT NULL,
    CONSTRAINT pk_kick_delivery PRIMARY KEY (subject_id, proxy_id)
);
