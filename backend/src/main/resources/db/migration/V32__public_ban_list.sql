-- Public ban list. Additive only: the template's public reason, the internal player UUID, and the listing columns of
-- a punishment. Existing rows stay unlisted and have no UUID. player_hash_v1 is the one implementation of the player
-- hash scheme (blackhole-ban-list-v1): the database derives it from the stored UUID, no key involved.
ALTER TABLE punishment_templates ADD COLUMN public_reason VARCHAR(200);

ALTER TABLE punishments ADD COLUMN player_uuid UUID;
ALTER TABLE punishments ADD COLUMN player_hash_v1 CHAR(64) GENERATED ALWAYS AS (
    encode(sha256((('blackhole-ban-list-v1:' || player_uuid::text))::bytea), 'hex')
) STORED;
ALTER TABLE punishments ADD COLUMN public_reason VARCHAR(200);
ALTER TABLE punishments ADD COLUMN public_kind VARCHAR(16);
ALTER TABLE punishments ADD COLUMN public_hash VARCHAR(22);
ALTER TABLE punishments ADD COLUMN public_issued_day BIGINT;
ALTER TABLE punishments ADD COLUMN public_expires_at BIGINT;
ALTER TABLE punishments ADD COLUMN public_revoked_at BIGINT;

CREATE UNIQUE INDEX ux_punishments_public_hash ON punishments (public_hash);
CREATE INDEX ix_punishments_public_order ON punishments (public_issued_day, public_hash);
CREATE INDEX ix_punishments_player_hash_v1 ON punishments (player_hash_v1);
