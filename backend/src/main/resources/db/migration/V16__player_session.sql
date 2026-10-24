-- Player session (appeal-portal capability): what a punished player gets for a good Ban ID login on the
-- public API. The token itself is a random 256 bit value shown once; only its SHA-256 (hex) is stored,
-- never the token and never the Ban ID. The session belongs to one punishment and reaches nothing else.
--
-- last_seen_at slides the idle limit (blackhole.public.session.idle), expires_at is the absolute limit
-- (blackhole.public.session.absolute); both are checked against the application clock, passed in by the
-- service, never the database clock. A sweeper deletes rows past either limit. A leak mark or a reissue of
-- the Ban ID deletes the sessions of that punishment in the same statement as the case change, and
-- deleting the punishment cascades. scope is FULL, or LIMITED for a leaked Ban ID (facts and notice only).
CREATE TABLE player_session (
    token_hash varchar(64) NOT NULL,
    punishment_identifier varchar(255) NOT NULL,
    scope varchar(8) NOT NULL,
    csrf varchar(64) NOT NULL,
    created_at bigint NOT NULL,
    last_seen_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT player_session_pkey PRIMARY KEY (token_hash),
    CONSTRAINT fk_player_session_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier) ON DELETE CASCADE,
    CONSTRAINT player_session_scope_check CHECK (scope IN ('FULL', 'LIMITED'))
);

CREATE INDEX idx_player_session_punishment ON player_session (punishment_identifier);
CREATE INDEX idx_player_session_expires_at ON player_session (expires_at);
CREATE INDEX idx_player_session_last_seen_at ON player_session (last_seen_at);
