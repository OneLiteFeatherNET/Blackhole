-- One ELO score per owner and punishment section. A missing row means the section's base score.
-- Replaces the chat_elo/gameplay_elo columns of elo_profiles, which are dropped in V6.
CREATE TABLE elo_scores (
    owner varchar(255) NOT NULL,
    section_key varchar(64) NOT NULL,
    score integer NOT NULL,
    updated_at bigint NOT NULL,
    CONSTRAINT pk_elo_scores PRIMARY KEY (owner, section_key),
    CONSTRAINT fk_elo_scores_section FOREIGN KEY (section_key)
        REFERENCES punishment_sections (section_key)
);

-- elo_events.track stays (nullable) for history written before sections existed; new events carry
-- section_key and leave track empty.
ALTER TABLE elo_events ADD COLUMN section_key varchar(64);
CREATE INDEX idx_elo_events_owner_section ON elo_events (owner, section_key);
