-- Discord account link (add-discord-bot-foundation). A staff member (Entra object id) or a player
-- (hashed owner, never the raw UUID) proves they own a Discord account with a short one-time code
-- typed as /link <code> in Discord: discord_link_code holds the live code of a subject (one row per
-- subject, only a SHA-256 hash of the code, never the code), discord_link the resulting pair.
--
-- A Discord user id is personal data. It lives only in discord_link, which is erasable: the person
-- unlinks it (/unlink), an admin removes it, or anonymizing the staff actor deletes it. It never
-- appears in events or logs. Both sides are unique per kind, so a race can never produce two links.
CREATE TABLE discord_link_code (
    owner_key varchar(160) NOT NULL,
    kind varchar(16) NOT NULL,
    subject varchar(128) NOT NULL,
    code_hash varchar(64) NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT discord_link_code_pkey PRIMARY KEY (owner_key),
    CONSTRAINT discord_link_code_code_hash_key UNIQUE (code_hash),
    CONSTRAINT discord_link_code_kind_check CHECK (kind IN ('STAFF', 'PLAYER'))
);

CREATE TABLE discord_link (
    id uuid NOT NULL,
    kind varchar(16) NOT NULL,
    subject varchar(128) NOT NULL,
    discord_user_id varchar(24) NOT NULL,
    linked_at bigint NOT NULL,
    CONSTRAINT discord_link_pkey PRIMARY KEY (id),
    CONSTRAINT discord_link_subject_key UNIQUE (kind, subject),
    CONSTRAINT discord_link_discord_user_key UNIQUE (kind, discord_user_id),
    CONSTRAINT discord_link_kind_check CHECK (kind IN ('STAFF', 'PLAYER'))
);
