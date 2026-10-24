-- Client fingerprints (client-fingerprint capability): a keyed hash of the client traits the proxy saw.
--
-- fingerprint is HMAC-SHA256 (64 hex characters) under a key of its own (BLACKHOLE_FINGERPRINT_KEY,
-- derived with HKDF); scheme names the canonical form and key generation ('v1'), so a later trait set or
-- key can coexist. owner_hash is the existing SHA-512 owner hash, never a raw UUID. No brand, channel or
-- mod is stored, here or anywhere: only the hash, and a hash alone cannot be turned back into them.
-- One row per (fingerprint, owner); a repeated sighting updates last_seen and occurrence_count. The rows
-- are short-lived (blackhole.evasion.fingerprint.retention-days, default 30) and are deleted with the
-- player's data. Generic fingerprints (plain vanilla, fabric, forge, neoforge) are never stored.
CREATE TABLE client_fingerprint (
    identifier uuid NOT NULL DEFAULT gen_random_uuid(),
    fingerprint varchar(64) NOT NULL,
    scheme varchar(8) NOT NULL,
    owner_hash varchar(128) NOT NULL,
    first_seen bigint NOT NULL,
    last_seen bigint NOT NULL,
    occurrence_count integer NOT NULL DEFAULT 1,
    CONSTRAINT client_fingerprint_pkey PRIMARY KEY (identifier),
    CONSTRAINT client_fingerprint_owner_unique UNIQUE (fingerprint, owner_hash)
);
CREATE INDEX client_fingerprint_owner ON client_fingerprint (owner_hash);
CREATE INDEX client_fingerprint_last_seen ON client_fingerprint (last_seen);
