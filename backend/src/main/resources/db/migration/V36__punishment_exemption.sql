-- Punishment exemption. A player is exempt when their Minecraft account is linked to a staff member
-- (LINKED_STAFF) or when the proxy reports the Velocity permission blackhole.exempt[.<section>] for them
-- (PERMISSION). One row per owner and source; the owner is the SHA-512 hash, never the raw UUID.
-- sections holds the comma-separated section keys of a PERMISSION row, or '*' for blackhole.exempt (all).
CREATE TABLE player_exemption (
    owner varchar(128) NOT NULL,
    source varchar(16) NOT NULL,
    sections varchar(512),
    staff_oid uuid,
    reported_at bigint NOT NULL,
    CONSTRAINT player_exemption_pkey PRIMARY KEY (owner, source),
    CONSTRAINT ck_player_exemption_source CHECK (source IN ('LINKED_STAFF', 'PERMISSION'))
);

-- An automatic ELO punishment that would have hit an exempt player, kept for an admin to apply or dismiss.
-- The score change itself stays on the score; only the punishment is held back.
CREATE TABLE exemption_review (
    id uuid NOT NULL,
    owner varchar(128) NOT NULL,
    section_key varchar(64) NOT NULL,
    tier varchar(16) NOT NULL,
    score_after integer NOT NULL,
    crossed_at bigint NOT NULL,
    state varchar(16) NOT NULL,
    decided_by_oid uuid,
    decided_at bigint,
    justification varchar(500),
    CONSTRAINT exemption_review_pkey PRIMARY KEY (id),
    CONSTRAINT ck_exemption_review_tier CHECK (tier IN ('SOFT', 'PERMANENT')),
    CONSTRAINT ck_exemption_review_state CHECK (state IN ('OPEN', 'APPLIED', 'DISMISSED'))
);
CREATE INDEX idx_exemption_review_state ON exemption_review (state, crossed_at);

-- Players who are already linked get their LINKED_STAFF row now. The owner is the SHA-512 of the
-- Minecraft UUID's canonical text, the same value as the backend's and Velocity's owner function.
INSERT INTO player_exemption (owner, source, sections, staff_oid, reported_at)
SELECT encode(sha512(convert_to(minecraft_uuid::text, 'UTF8')), 'hex'), 'LINKED_STAFF', NULL, oid, linked_at
FROM staff_link
ON CONFLICT (owner, source) DO NOTHING;
