-- The level history also records the changes made in game (through the proxy) and from the Discord bot.
ALTER TABLE join_guard_change DROP CONSTRAINT ck_join_guard_change_source;
ALTER TABLE join_guard_change ADD CONSTRAINT ck_join_guard_change_source
    CHECK (source IN ('MANUAL', 'UNATTENDED', 'CLEARED', 'IN_GAME', 'DISCORD'));
