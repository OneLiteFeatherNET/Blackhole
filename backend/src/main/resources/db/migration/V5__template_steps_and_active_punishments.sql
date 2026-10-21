-- Escalation: a template lists ordered steps (duration + ELO cost) and belongs to an escalation
-- group with an optional window. A punishment records the group, the chosen step, the step count
-- and the deducted cost, so its history stays explainable after the template is edited.
-- Durations are ISO-8601 strings, validated in the application layer.
CREATE TABLE punishment_template_steps (
    template_identifier uuid NOT NULL,
    step_index integer NOT NULL,
    duration varchar(32) NOT NULL,
    elo_cost integer NOT NULL,
    CONSTRAINT pk_punishment_template_steps PRIMARY KEY (template_identifier, step_index),
    CONSTRAINT fk_punishment_template_steps_template FOREIGN KEY (template_identifier)
        REFERENCES punishment_templates (identifier)
);

ALTER TABLE punishment_templates ADD COLUMN escalation_group varchar(64);
ALTER TABLE punishment_templates ADD COLUMN escalation_window varchar(32);

ALTER TABLE punishments ADD COLUMN escalation_group varchar(64);
ALTER TABLE punishments ADD COLUMN step_index integer;
ALTER TABLE punishments ADD COLUMN step_count integer;
ALTER TABLE punishments ADD COLUMN elo_cost integer;
CREATE INDEX idx_punishments_owner_group_created ON punishments (owner, escalation_group, created_at);

-- One active punishment per owner and section. Replaces the two fixed slots of
-- punishment_profiles (dropped in V6). The primary key makes a second concurrent write to the
-- same slot fail instead of silently double-occupying it.
CREATE TABLE active_punishments (
    owner varchar(255) NOT NULL,
    section_key varchar(64) NOT NULL,
    punishment_identifier varchar(255) NOT NULL,
    CONSTRAINT uk_active_punishments_punishment UNIQUE (punishment_identifier),
    CONSTRAINT pk_active_punishments PRIMARY KEY (owner, section_key),
    CONSTRAINT fk_active_punishments_profile FOREIGN KEY (owner)
        REFERENCES punishment_profiles (owner),
    CONSTRAINT fk_active_punishments_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier)
);
