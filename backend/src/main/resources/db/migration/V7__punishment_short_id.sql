-- The short ID players quote in an appeal (PunishmentShortId): the upper case hex of the first four
-- bytes of the punishment's UUID. The identifier is that UUID as 22 character URL-safe Base64, or
-- (not produced by the backend) a plain UUID. An identifier in neither format gets the first four
-- bytes of its MD5, as PunishmentShortId.storageKey does. Short IDs are not unique, so the index
-- is not either.
ALTER TABLE punishments ADD COLUMN short_id varchar(8);

UPDATE punishments SET short_id = CASE
    WHEN identifier ~ '^[A-Za-z0-9_-]{22}$'
        THEN upper(encode(substring(decode(translate(identifier, '-_', '+/') || '==', 'base64') from 1 for 4), 'hex'))
    WHEN identifier ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN upper(left(identifier, 8))
    ELSE upper(left(md5(identifier), 8))
END;

ALTER TABLE punishments ALTER COLUMN short_id SET NOT NULL;
CREATE INDEX idx_punishments_short_id ON punishments (short_id);
