-- Templates belong to a section and are addressed by a unique key (the command slug).
-- punishment_templates.type and elo_delta stay (type nullable, elo_delta defaulting to 0) but are
-- no longer read. template_key is nullable for rows that predate this change; the application
-- layer requires it on every template it writes.
ALTER TABLE punishment_templates ADD COLUMN template_key varchar(64);
ALTER TABLE punishment_templates ADD COLUMN section_key varchar(64);
ALTER TABLE punishment_templates ADD CONSTRAINT uk_punishment_templates_key UNIQUE (template_key);
ALTER TABLE punishment_templates ADD CONSTRAINT fk_punishment_templates_section
    FOREIGN KEY (section_key) REFERENCES punishment_sections (section_key);

-- Punishments know their owner and section. permanent marks the system's escalation punishments
-- (no template, no expiry); lifted marks punishments that were revoked or lifted by a granted
-- appeal, which escalation step selection must not count.
ALTER TABLE punishments ADD COLUMN owner varchar(255);
ALTER TABLE punishments ADD COLUMN section_key varchar(64);
ALTER TABLE punishments ADD COLUMN permanent boolean NOT NULL DEFAULT false;
ALTER TABLE punishments ADD COLUMN lifted boolean NOT NULL DEFAULT false;
ALTER TABLE punishments ADD COLUMN created_at bigint;
ALTER TABLE punishments ADD CONSTRAINT fk_punishments_section
    FOREIGN KEY (section_key) REFERENCES punishment_sections (section_key);
