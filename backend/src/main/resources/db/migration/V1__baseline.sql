-- Baseline schema (PostgreSQL). Flyway owns the schema; Hibernate only validates it
-- (hbm2ddl.auto=validate). Migrations are append-only: never edit an applied file, add
-- V2__<description>.sql instead.
--
-- shortcut: no CHECK constraints (JSON validity, ordinal enum ranges) - enforced in the
-- application layer. smallint columns are ordinal enums, jsonb columns are metadata.

CREATE TABLE punishment_templates (
    identifier uuid NOT NULL,
    meta_data jsonb,
    reason varchar(255),
    type smallint,
    elo_delta integer NOT NULL DEFAULT 0,
    CONSTRAINT punishment_templates_pkey PRIMARY KEY (identifier)
);
CREATE INDEX "IDXfb0n1048swedl734iua11xgh6" ON punishment_templates (identifier);

CREATE TABLE punishments (
    identifier varchar(255) NOT NULL,
    meta_data jsonb,
    scope varchar(255),
    source uuid,
    type smallint,
    template_identifier uuid,
    CONSTRAINT punishments_pkey PRIMARY KEY (identifier),
    CONSTRAINT "FKsystxtartkbg3pelwij5y444y" FOREIGN KEY (template_identifier)
        REFERENCES punishment_templates (identifier)
);
CREATE INDEX "IDXbg1863u12hbsiqggy1kxv84gj" ON punishments (identifier);

CREATE TABLE punishment_profiles (
    owner varchar(255) NOT NULL,
    meta_data jsonb,
    active_ban_identifier varchar(255),
    active_chat_ban_identifier varchar(255),
    CONSTRAINT punishment_profiles_pkey PRIMARY KEY (owner),
    CONSTRAINT "UKjenhq1urh55q6qbhxbor43mpr" UNIQUE (active_ban_identifier),
    CONSTRAINT "UK3bfpsh5gh8uybcnqn6ist0ju3" UNIQUE (active_chat_ban_identifier),
    CONSTRAINT "FKrg26gk6cng7up213jiux4ugap" FOREIGN KEY (active_ban_identifier)
        REFERENCES punishments (identifier),
    CONSTRAINT "FK44vycpq38twfwo4pm864edwtf" FOREIGN KEY (active_chat_ban_identifier)
        REFERENCES punishments (identifier)
);
CREATE INDEX "IDXs2rqilyfwjja2eh18bvdkgssg" ON punishment_profiles (owner);

CREATE TABLE punishment_profiles_punishments (
    punishment_profile_entity_owner varchar(255) NOT NULL,
    history_identifier varchar(255) NOT NULL,
    CONSTRAINT "UK72hdqv4fhab29f7yg9a80d1nt" UNIQUE (history_identifier),
    CONSTRAINT fk_punishment_profiles_punishments_owner FOREIGN KEY (punishment_profile_entity_owner)
        REFERENCES punishment_profiles (owner),
    CONSTRAINT "FK7yf2av2hp2nfq84philn3588l" FOREIGN KEY (history_identifier)
        REFERENCES punishments (identifier)
);

CREATE TABLE reports (
    identifier uuid NOT NULL,
    category smallint,
    created_at bigint NOT NULL,
    description varchar(1000),
    meta_data jsonb,
    reported_hash varchar(255),
    reporter_hash varchar(255),
    resolution_note varchar(1000),
    resolved_by uuid,
    status smallint,
    updated_at bigint NOT NULL,
    CONSTRAINT reports_pkey PRIMARY KEY (identifier)
);
CREATE INDEX "IDXcdecaabcx0057rmwougq4lln8" ON reports (identifier);
CREATE INDEX "IDXhucb8heess4o7s9d641njq2e0" ON reports (reporter_hash);

CREATE TABLE report_evidence_references (
    report_identifier uuid NOT NULL,
    evidence_identifier uuid,
    CONSTRAINT "FK27b8gnu183fwcp3f8ixk9xw49" FOREIGN KEY (report_identifier)
        REFERENCES reports (identifier)
);

CREATE TABLE punishment_evidence (
    identifier uuid NOT NULL,
    captured_content_hash varchar(255),
    evidence_type smallint,
    meta_data jsonb,
    reference_id varchar(255),
    retention_expires_at bigint,
    punishment_identifier varchar(255),
    CONSTRAINT punishment_evidence_pkey PRIMARY KEY (identifier),
    CONSTRAINT "FK1fcfw3b9ah8l5ywj1ksemk98r" FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier)
);
CREATE INDEX "IDXeowdvehtn2mq9ratnjjt10mx0" ON punishment_evidence (identifier);

CREATE TABLE appeals (
    identifier uuid NOT NULL,
    appellant_hash varchar(255),
    created_at bigint NOT NULL,
    decided_by uuid,
    decision_note varchar(2000),
    eligibility_check_result jsonb,
    meta_data jsonb,
    statement varchar(2000),
    status smallint,
    updated_at bigint NOT NULL,
    punishment_identifier varchar(255),
    CONSTRAINT appeals_pkey PRIMARY KEY (identifier),
    CONSTRAINT "FKqb76lk2ldlufmeevnpme8l94m" FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier)
);
CREATE INDEX "IDXkumdsu9qt44a38mg4k9ngyp87" ON appeals (identifier);
CREATE INDEX "IDX9faypy3gxh4xmiqii41ypsj5k" ON appeals (appellant_hash);

CREATE TABLE elo_profiles (
    owner varchar(255) NOT NULL,
    chat_elo integer NOT NULL,
    chat_elo_updated_at bigint NOT NULL,
    gameplay_elo integer NOT NULL,
    gameplay_elo_updated_at bigint NOT NULL,
    meta_data jsonb,
    CONSTRAINT elo_profiles_pkey PRIMARY KEY (owner)
);
CREATE INDEX "IDX5k6jltfkea67v5ai92cey03u7" ON elo_profiles (owner);

CREATE TABLE elo_events (
    identifier uuid NOT NULL,
    created_at bigint NOT NULL,
    delta integer NOT NULL,
    meta_data jsonb,
    owner varchar(255),
    reason_code smallint,
    resulting_score integer NOT NULL,
    source_evidence_id uuid,
    track smallint,
    CONSTRAINT elo_events_pkey PRIMARY KEY (identifier)
);
CREATE INDEX "IDX1vli9egxhskmxh09x7ippe01p" ON elo_events (identifier);
CREATE INDEX "IDX8kfktno2r37fryp23hbrywxge" ON elo_events (owner);

CREATE TABLE ip_correlation_tokens (
    identifier uuid NOT NULL,
    first_seen bigint NOT NULL,
    last_seen bigint NOT NULL,
    meta_data jsonb,
    occurrence_count integer NOT NULL,
    owner_hash varchar(255),
    token varchar(255),
    CONSTRAINT ip_correlation_tokens_pkey PRIMARY KEY (identifier)
);
CREATE INDEX "IDXej580909u0b2cronpaairkgty" ON ip_correlation_tokens (identifier);
CREATE INDEX "IDXdchy5i8x4p42k38necseou23o" ON ip_correlation_tokens (token);
