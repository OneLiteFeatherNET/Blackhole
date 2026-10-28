-- A warning is a punishment row with kind WARN: stored like a kick, never active, and counted for
-- escalation (within the template's warn_window when it sets one). acknowledged_at is set once, by the
-- player's acknowledgement. Existing rows keep their values.
ALTER TABLE punishments DROP CONSTRAINT ck_punishments_kind;
ALTER TABLE punishments ADD CONSTRAINT ck_punishments_kind CHECK (kind IN ('PUNISHMENT', 'KICK', 'WARN'));
ALTER TABLE punishments ADD COLUMN acknowledged_at bigint;
CREATE INDEX idx_punishments_owner_kind_acked ON punishments (owner, kind, acknowledged_at);

-- A WARN step has no duration, like a KICK step.
ALTER TABLE punishment_template_steps ADD CONSTRAINT ck_punishment_template_steps_kind CHECK (kind IN ('PUNISH', 'KICK', 'WARN'));

-- Warnings older than this stop counting towards escalation; null means the escalation window applies.
ALTER TABLE punishment_templates ADD COLUMN warn_window varchar(32);
