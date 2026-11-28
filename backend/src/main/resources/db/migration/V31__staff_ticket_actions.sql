-- Discord staff ticket actions. Additive only: the role snapshot of a staff member, and where a ticket freeze came from.
-- A freeze that exists today was set by the leak path, so every frozen row is LEAK.
ALTER TABLE staff_actor ADD COLUMN roles VARCHAR(128);
ALTER TABLE staff_actor ADD COLUMN roles_seen_at BIGINT;

ALTER TABLE discord_ticket ADD COLUMN freeze_source VARCHAR(8);
UPDATE discord_ticket SET freeze_source = 'LEAK' WHERE frozen;
