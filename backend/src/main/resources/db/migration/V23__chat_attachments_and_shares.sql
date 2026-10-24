-- Chat files and evidence sharing (appeal-chat, evidence-sharing and punishment-evidence capabilities).
--
-- provenance says who supplied an evidence item: STAFF (0) or PLAYER (1), an append-only ordinal like kind and
-- status (EvidenceProvenance). Every existing row is staff evidence, so the default is 0. Player-supplied items
-- are labelled "player-supplied, unverified" and are not part of the team evidence count.
--
-- A chat file is staged first (punishment_identifier NULL, correlation_id 'chat:<scope>') and bound by the message
-- that names it; binding sets the punishment and keeps the correlation id, so the files of an appeal can be counted
-- for the quota. The correlation index serves that count.
ALTER TABLE evidence_items ADD COLUMN provenance smallint NOT NULL DEFAULT 0;
CREATE INDEX idx_evidence_items_correlation ON evidence_items (correlation_id) WHERE correlation_id IS NOT NULL;

-- One row per share of one evidence item with the player of one appeal. The state is mutable (a share can be
-- revoked), the history is the two case events evidence.shared and evidence.share_revoked. An EXCERPT share stores
-- line indexes only (selection, sorted, at most blackhole.chat.share.max-lines) and never a copy of the lines; the
-- reader renders them from the evidence when the player asks, so a deleted evidence item ends the share without
-- another job. evidence_id has deliberately no foreign key for the same reason: the row outlives the item and reads
-- as UNAVAILABLE. The rows go with the punishment and with the appeal.
-- appellant_speaker is the speaker label shown as "You"; confirmed_minimised is always true (the service refuses a
-- share without the confirmation); shared_by_oid is the confirming staff member. mode is validated by the
-- application (FILE or EXCERPT), not by a CHECK constraint (see .claude/rules/database.md).
-- The unique partial index makes a repeat of the same item and selection while the first share is active the same share.
CREATE TABLE evidence_share (
    share_id uuid NOT NULL,
    punishment_identifier varchar(255) NOT NULL,
    appeal_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    mode varchar(8) NOT NULL,
    selection integer[] NOT NULL DEFAULT '{}',
    appellant_speaker varchar(200),
    confirmed_minimised boolean NOT NULL,
    shared_by_oid uuid NOT NULL,
    shared_at bigint NOT NULL,
    revoked_at bigint,
    revoked_by_oid uuid,
    revoke_reason varchar(16),
    CONSTRAINT evidence_share_pkey PRIMARY KEY (share_id),
    CONSTRAINT fk_evidence_share_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_share_appeal FOREIGN KEY (appeal_id)
        REFERENCES appeals (identifier) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_evidence_share_active ON evidence_share (appeal_id, evidence_id, selection) WHERE revoked_at IS NULL;
CREATE INDEX idx_evidence_share_punishment ON evidence_share (punishment_identifier);
CREATE INDEX idx_evidence_share_evidence ON evidence_share (evidence_id);
