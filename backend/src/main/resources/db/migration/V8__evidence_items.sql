-- Evidence attached to punishments (punishment-evidence capability). Separate from the legacy
-- hash-only punishment_evidence table, which stays untouched.
--
-- kind and status are ordinal enums stored as smallint (EvidenceKind: ANTICHEAT, REPLAY, CHAT_LOG,
-- LINK, FILE; EvidenceStatus: VALID, REVOKED, LIFTED). Their ranges and the JSON validity of payload
-- are enforced by the application, not by CHECK constraints (see .claude/rules/database.md).
--
-- A row with a NULL punishment_identifier is pending: it waits for a punishment that claims its
-- (owner, correlation_id). Evidence lives exactly as long as its punishment, hence ON DELETE CASCADE.
CREATE TABLE evidence_items (
    identifier uuid NOT NULL,
    punishment_identifier varchar(255),
    owner varchar(255) NOT NULL,
    correlation_id varchar(64),
    kind smallint NOT NULL,
    source_system varchar(64) NOT NULL,
    external_ref varchar(2048),
    payload jsonb,
    payload_schema_version integer NOT NULL DEFAULT 1,
    captured_at bigint,
    created_at bigint NOT NULL,
    created_by uuid,
    file_key varchar(255),
    file_name varchar(255),
    file_content_type varchar(100),
    file_size bigint,
    file_sha256 varchar(64),
    status smallint NOT NULL DEFAULT 0,
    status_changed_at bigint,
    lifted_by_appeal uuid,
    CONSTRAINT evidence_items_pkey PRIMARY KEY (identifier),
    CONSTRAINT fk_evidence_items_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier) ON DELETE CASCADE
);
CREATE INDEX idx_evidence_items_owner ON evidence_items (owner);
CREATE INDEX idx_evidence_items_owner_correlation ON evidence_items (owner, correlation_id);
CREATE INDEX idx_evidence_items_punishment ON evidence_items (punishment_identifier);
CREATE INDEX idx_evidence_items_status ON evidence_items (status);
CREATE INDEX idx_evidence_items_file_key ON evidence_items (file_key);

-- Append-only access trail. Deliberately no foreign keys: entries outlive the evidence they
-- describe, and they never contain evidence content. action is the EvidenceAuditAction ordinal
-- (ATTACHED, VIEWED, VIEWED_INACTIVE, DOWNLOADED, DELETED).
CREATE TABLE evidence_audit (
    identifier uuid NOT NULL,
    evidence_identifier uuid NOT NULL,
    punishment_identifier varchar(255),
    owner varchar(255) NOT NULL,
    action smallint NOT NULL,
    actor uuid,
    reason varchar(32),
    note varchar(500),
    occurred_at bigint NOT NULL,
    CONSTRAINT evidence_audit_pkey PRIMARY KEY (identifier)
);
CREATE INDEX idx_evidence_audit_evidence ON evidence_audit (evidence_identifier);
CREATE INDEX idx_evidence_audit_owner ON evidence_audit (owner);
CREATE INDEX idx_evidence_audit_occurred_at ON evidence_audit (occurred_at);
