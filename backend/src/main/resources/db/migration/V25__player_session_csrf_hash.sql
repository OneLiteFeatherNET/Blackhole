-- Portal CSRF value, hashed at rest (harden-oauth-and-jwt, audit P5). Until now player_session.csrf held the
-- raw value the portal sends back as X-CSRF-Token; from now on it holds the lower-case hex SHA-256 of that
-- value, the same way token_hash holds the hash of the session token. A hex SHA-256 is 64 characters, so it
-- fits the existing varchar(64) column.
--
-- The rows that exist are converted in place, with the digest the service computes, so a live session stays
-- valid and its holder is not signed out. The step cannot be undone (the raw value is gone); to roll back,
-- end all portal sessions with DELETE FROM player_session (safe: a session lasts 4 hours at most).
-- V16 is not edited.
UPDATE player_session
SET csrf = encode(sha256(convert_to(csrf, 'UTF8')), 'hex');
