-- Client rule hits (client-rules capability): one row per hit the proxy reports.
--
-- A hit stores no client trait at all: no brand, channel, mod or command text, and not the value a
-- glob matched. The rule key already says what matched. owner_hash is the existing SHA-512 owner
-- hash, never a raw UUID; server_name is an infrastructure name. The rows are short-lived
-- (blackhole.clientrules.hit-retention-days, default 30) and are deleted with the player's data;
-- client_rule.hit_count keeps the running total after they expire. Hits cascade with their rule.
CREATE TABLE client_rule_hit (
    identifier uuid NOT NULL DEFAULT gen_random_uuid(),
    rule_key varchar(64) NOT NULL REFERENCES client_rule (rule_key) ON DELETE CASCADE,
    owner_hash varchar(128) NOT NULL,
    at bigint NOT NULL,
    action_taken varchar(16) NOT NULL,
    enforced boolean NOT NULL DEFAULT false,
    bypassed boolean NOT NULL DEFAULT false,
    server_name varchar(64),
    CONSTRAINT client_rule_hit_pkey PRIMARY KEY (identifier),
    CONSTRAINT client_rule_hit_action_check CHECK (action_taken IN ('NOTIFY', 'DENY', 'TEMPLATE'))
);
CREATE INDEX client_rule_hit_rule_at ON client_rule_hit (rule_key, at);
CREATE INDEX client_rule_hit_owner ON client_rule_hit (owner_hash);
CREATE INDEX client_rule_hit_at ON client_rule_hit (at);
