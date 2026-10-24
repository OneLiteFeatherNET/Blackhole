-- Client rules (client-rules capability): staff-defined rules over the client brand, legacy mod list,
-- registered plugin channels, protocol version and commands, enforced by the proxy.
--
-- A rule is addressed by its client-chosen key. The match data lives in child tables with a surrogate
-- key, so one data-modifying statement can replace a rule's children (delete and insert) without a
-- unique-key clash between its own sub-statements. Child rows cascade with the rule.
-- Sections are referenced by key without a foreign key: a section removed later makes the rule cover
-- nothing for that key (fail open). The template is a real foreign key with RESTRICT, so a template a
-- rule applies cannot be deleted. hit_count is the running total kept after single hits expire.
-- recorded_by_oid is the verified staff member (no foreign key, like the other recorded_by_oid columns).
-- Pattern kinds need patterns and PROTOCOL needs a range; both are checked by the service, because a
-- CHECK cannot look into a child table.
CREATE TABLE client_rule (
    rule_key varchar(64) NOT NULL,
    display_name varchar(128) NOT NULL,
    kind varchar(16) NOT NULL,
    protocol_min integer,
    protocol_max integer,
    negate boolean NOT NULL DEFAULT false,
    scope_kind varchar(16) NOT NULL DEFAULT 'GLOBAL',
    target_kind varchar(32) NOT NULL DEFAULT 'PATTERN',
    hide_command boolean NOT NULL DEFAULT false,
    action varchar(16) NOT NULL DEFAULT 'NOTIFY',
    deny_message varchar(200),
    template_key varchar(64),
    acknowledged_spoofable boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    hit_count bigint NOT NULL DEFAULT 0,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    recorded_by_oid uuid,
    CONSTRAINT client_rule_pkey PRIMARY KEY (rule_key),
    CONSTRAINT client_rule_kind_check CHECK (kind IN ('BRAND', 'MOD', 'CHANNEL', 'PROTOCOL', 'COMMAND')),
    CONSTRAINT client_rule_scope_check CHECK (scope_kind IN ('GLOBAL', 'SERVERS')),
    CONSTRAINT client_rule_action_check CHECK (action IN ('NOTIFY', 'DENY', 'TEMPLATE')),
    CONSTRAINT client_rule_range_check CHECK (
        (kind = 'PROTOCOL' AND protocol_min IS NOT NULL AND protocol_max IS NOT NULL AND protocol_min >= 0 AND protocol_min <= protocol_max)
        OR (kind <> 'PROTOCOL' AND protocol_min IS NULL AND protocol_max IS NULL)),
    CONSTRAINT client_rule_template_check CHECK ((action = 'TEMPLATE') = (template_key IS NOT NULL)),
    CONSTRAINT client_rule_template_fk FOREIGN KEY (template_key)
        REFERENCES punishment_templates (template_key) ON DELETE RESTRICT
);

CREATE TABLE client_rule_pattern (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_key varchar(64) NOT NULL REFERENCES client_rule (rule_key) ON DELETE CASCADE,
    position integer NOT NULL,
    pattern varchar(128) NOT NULL
);
CREATE INDEX client_rule_pattern_rule ON client_rule_pattern (rule_key, position);

CREATE TABLE client_rule_server (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_key varchar(64) NOT NULL REFERENCES client_rule (rule_key) ON DELETE CASCADE,
    position integer NOT NULL,
    server_name varchar(64) NOT NULL
);
CREATE INDEX client_rule_server_rule ON client_rule_server (rule_key, position);

CREATE TABLE client_rule_section (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_key varchar(64) NOT NULL REFERENCES client_rule (rule_key) ON DELETE CASCADE,
    position integer NOT NULL,
    section_key varchar(64) NOT NULL
);
CREATE INDEX client_rule_section_rule ON client_rule_section (rule_key, position);
