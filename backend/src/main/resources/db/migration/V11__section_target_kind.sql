-- What the entries of a SERVER section's server list mean: PATTERN (glob over the Velocity server
-- name, the behaviour of every existing section), CLOUDNET_TASK, CLOUDNET_GROUP or CLOUDNET_SERVICE.
-- The allowed values are enforced in the application layer (ServerTargetKind).
ALTER TABLE punishment_sections
    ADD COLUMN target_kind varchar(24) NOT NULL DEFAULT 'PATTERN';
