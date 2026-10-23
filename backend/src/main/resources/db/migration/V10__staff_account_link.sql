-- Staff account link (link-staff-minecraft-accounts). A signed-in staff member (an Entra object id,
-- "oid") proves they own a Minecraft account with a short one-time code: staff_link_code holds the
-- live code of an oid (one row per oid, only a SHA-256 hash of the code, never the code), staff_link
-- the resulting one-to-one pair. Both sides are unique, so a race can never produce two links.
-- Staff personal data like staff_actor: ended by unlinking or anonymizing the person.
--
-- actor_oid is the oid a link attributes an in-game action to (written when the proxy acts on behalf
-- of a linked Minecraft UUID). It stays NULL for every row that existed before this migration and for
-- every action by an unlinked UUID: creating a link never rewrites history. Like recorded_by_oid it has
-- no foreign key, so unlinking, anonymizing or pruning never blocks history. Appeals get no actor_oid
-- (reviews are web-only, decided_by already is the oid). Reports get the column for symmetry with
-- the other records; a report is resolved in the panel today, so it stays NULL.
CREATE TABLE staff_link_code (
    oid uuid NOT NULL,
    code_hash varchar(64) NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT staff_link_code_pkey PRIMARY KEY (oid),
    CONSTRAINT staff_link_code_code_hash_key UNIQUE (code_hash)
);

CREATE TABLE staff_link (
    oid uuid NOT NULL,
    minecraft_uuid uuid NOT NULL,
    linked_at bigint NOT NULL,
    linked_by_oid uuid NOT NULL,
    CONSTRAINT staff_link_pkey PRIMARY KEY (oid),
    CONSTRAINT staff_link_minecraft_uuid_key UNIQUE (minecraft_uuid)
);

ALTER TABLE punishments ADD COLUMN actor_oid uuid;
ALTER TABLE reports ADD COLUMN actor_oid uuid;
ALTER TABLE evidence_items ADD COLUMN actor_oid uuid;
ALTER TABLE evidence_audit ADD COLUMN actor_oid uuid;
