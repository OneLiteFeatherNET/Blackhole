-- Dropped once no code reads the legacy structures any more. elo_profiles held nothing but the two
-- track scores (plus an unused metadata column), so the whole table goes. Deployments start on a
-- fresh database; no rows are migrated.
DROP TABLE elo_profiles;

ALTER TABLE punishment_profiles DROP COLUMN active_ban_identifier;
ALTER TABLE punishment_profiles DROP COLUMN active_chat_ban_identifier;
