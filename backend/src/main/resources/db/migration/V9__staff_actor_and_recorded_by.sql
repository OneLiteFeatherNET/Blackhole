-- Verified actor on writes (team SSO). staff_actor holds one row per authenticated principal
-- (an Entra object id): only the oid, its kind, the display name from the token's name claim and
-- the first/last time it wrote. kind is HUMAN or SERVICE, stored as text. No email, UPN or token
-- is ever stored. display_name is cleared by the admin anonymize route; the row and the oid stay.
--
-- recorded_by_oid is the token's oid of whoever wrote the record. It is NULL for every row that
-- existed before this migration (written unverified, deliberately not backfilled) and has no
-- foreign key to staff_actor, so anonymizing or pruning staff_actor never blocks history.
CREATE TABLE staff_actor (
    oid uuid NOT NULL,
    kind varchar(16) NOT NULL,
    display_name varchar(256),
    first_seen_at bigint NOT NULL,
    last_seen_at bigint NOT NULL,
    CONSTRAINT staff_actor_pkey PRIMARY KEY (oid)
);

ALTER TABLE punishments ADD COLUMN recorded_by_oid uuid;
ALTER TABLE reports ADD COLUMN recorded_by_oid uuid;
ALTER TABLE appeals ADD COLUMN recorded_by_oid uuid;
ALTER TABLE evidence_items ADD COLUMN recorded_by_oid uuid;
ALTER TABLE evidence_audit ADD COLUMN recorded_by_oid uuid;
