-- Punishment sections. Each section owns a player's ELO score, its thresholds and how it is
-- enforced (MUTE / BAN / SERVER). enforcement is stored as a string; the allowed values are
-- enforced in the application layer (EnforcementKind).
CREATE TABLE punishment_sections (
    section_key varchar(64) NOT NULL,
    display_name varchar(128) NOT NULL,
    enforcement varchar(16) NOT NULL,
    base_score integer NOT NULL,
    soft_threshold integer,
    soft_duration varchar(32),
    permanent_threshold integer NOT NULL,
    pardon_score integer NOT NULL,
    permanent_reason varchar(255) NOT NULL,
    decay_per_day integer NOT NULL DEFAULT 10,
    enabled boolean NOT NULL DEFAULT true,
    CONSTRAINT punishment_sections_pkey PRIMARY KEY (section_key)
);

INSERT INTO punishment_sections
    (section_key, display_name, enforcement, base_score, soft_threshold, soft_duration,
     permanent_threshold, pardon_score, permanent_reason, decay_per_day, enabled)
VALUES
    ('chat', 'Chat', 'MUTE', 1000, 700, 'PT24H', 300, 700,
     'Permanently muted after repeated chat violations', 10, true),
    ('network', 'Network', 'BAN', 1000, 700, 'P3D', 300, 700,
     'Permanently banned after repeated violations', 10, true);

-- Server name patterns (globs) of SERVER sections, one row per pattern. Whether a section may
-- list patterns at all, and that none matches every server, is enforced in the application
-- layer (ServerPatterns).
CREATE TABLE punishment_section_servers (
    section_key varchar(64) NOT NULL,
    pattern varchar(64) NOT NULL,
    CONSTRAINT pk_punishment_section_servers PRIMARY KEY (section_key, pattern),
    CONSTRAINT fk_punishment_section_servers_section FOREIGN KEY (section_key)
        REFERENCES punishment_sections (section_key)
);
