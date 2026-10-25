-- Staff display name and avatar (staff-profile). Each staff account (an Entra object id, "oid") may set a
-- display name that players see instead of "Moderation team". The default is empty: the Entra name of the
-- sign-in is never copied in. display_name_folded is the NFKC, lower-cased form; the partial unique index makes
-- two named profiles impossible to hold the same folded name. avatar_key is the keyed hash of the linked
-- Minecraft account (never the UUID); it is NULL while unlinked. anonymized_at marks an erased person.
--
-- Timestamps are epoch milliseconds, as in staff_link. No backfill: an existing staff member has no profile and
-- no name, and existing chat events keep their generic label (payload version 1).
CREATE TABLE staff_profile (
    oid uuid NOT NULL,
    display_name varchar(32),
    display_name_folded varchar(32),
    avatar_mode varchar(16) NOT NULL DEFAULT 'AUTO',
    avatar_key varchar(32),
    notice_acknowledged_at bigint,
    updated_at bigint NOT NULL,
    anonymized_at bigint,
    CONSTRAINT staff_profile_pkey PRIMARY KEY (oid)
);

CREATE UNIQUE INDEX staff_profile_display_name_folded_key ON staff_profile (display_name_folded)
    WHERE display_name_folded IS NOT NULL;

-- The cache of 64 px heads, one PNG per avatar key. Deleted on unlink and anonymization, refreshed weekly.
CREATE TABLE staff_avatar (
    avatar_key varchar(32) NOT NULL,
    png bytea NOT NULL,
    fetched_at bigint NOT NULL,
    CONSTRAINT staff_avatar_pkey PRIMARY KEY (avatar_key)
);
