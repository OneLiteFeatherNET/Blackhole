-- Ban ID (ban-id capability): the 8 character credential a player opens the appeal portal with.
-- Never stored in clear. ban_id_hash is HMAC-SHA256(key, normalised Ban ID) for lookups (unique);
-- ban_id_enc is AES-256-GCM(nonce || ciphertext, punishment identifier as AAD) for the staff reveal.
-- Both come from the secret BLACKHOLE_BANID_KEY, so they cannot be computed in SQL: existing rows
-- keep NULL here until the application's BanIdBackfill issues them (idempotent, only rows without
-- a hash). A punishment without a hash behaves as an unknown Ban ID. ban_id_gen is the generation:
-- 1 for the first, +1 per reissue.
ALTER TABLE punishments ADD COLUMN ban_id_hash bytea;
ALTER TABLE punishments ADD COLUMN ban_id_enc bytea;
ALTER TABLE punishments ADD COLUMN ban_id_gen integer NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX uq_punishments_ban_id_hash ON punishments (ban_id_hash);

-- Leak state of the current Ban ID generation (set by the leak mark, cleared by a reissue).
ALTER TABLE punishment_case ADD COLUMN ban_id_leaked_at bigint;
ALTER TABLE punishment_case ADD COLUMN ban_id_leak_reason varchar(16);

-- At most one open appeal (SUBMITTED, ELIGIBLE_PENDING_REVIEW, IN_REVIEW = 0, 1, 2) per punishment.
-- Nothing enforced that before, so existing data may hold several. They must not fail the upgrade and
-- no history is changed: of the open appeals of one punishment the newest by created_at (identifier
-- breaks ties) stays under the rule and every older one is flagged legacy_duplicate_open, which takes
-- it out of the index. Staff close the flagged ones by hand; the flag changes no status.
ALTER TABLE appeals ADD COLUMN legacy_duplicate_open boolean NOT NULL DEFAULT false;

UPDATE appeals a SET legacy_duplicate_open = true
WHERE a.status IN (0, 1, 2) AND a.punishment_identifier IS NOT NULL
  AND EXISTS (SELECT 1 FROM appeals newer
              WHERE newer.punishment_identifier = a.punishment_identifier AND newer.status IN (0, 1, 2)
                AND (newer.created_at, newer.identifier) > (a.created_at, a.identifier));

CREATE UNIQUE INDEX uq_appeals_one_open_per_punishment ON appeals (punishment_identifier)
    WHERE status IN (0, 1, 2) AND punishment_identifier IS NOT NULL AND NOT legacy_duplicate_open;
