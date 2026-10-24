-- The player's seen marker of a case (appeal-chat, "Seen"): the highest seq that the player's conversation read has returned.
-- It is one number, with no timestamp and no row per message. Staff show "Seen" under the last staff message at or below it.
-- It only grows (the store uses GREATEST, so a stale read cannot unsee a message), and 0 means the player has read nothing yet.
ALTER TABLE punishment_case ADD COLUMN player_seen_seq bigint NOT NULL DEFAULT 0;
