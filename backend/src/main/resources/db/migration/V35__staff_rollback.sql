-- Rollback of one staff actor's punishments (add-staff-rollback). A preview freezes the set of
-- punishments an admin would undo into staff_rollback_item; the job row carries the selector, the
-- window, the reason and the lease the runner takes while it executes. Additive only.
--
-- Exactly one selector is set: a staff member's oid (a HUMAN staff_actor) or the Minecraft UUID of an
-- unlinked in-game account. The state machine is PREVIEW -> RUNNING -> COMPLETED, or to CANCELLED.
CREATE TABLE staff_rollback_job (
    id uuid NOT NULL,
    state varchar(16) NOT NULL,
    selector_oid uuid,
    selector_minecraft_uuid uuid,
    window_from bigint NOT NULL,
    window_to bigint NOT NULL,
    include_marks boolean NOT NULL DEFAULT false,
    suspend_link boolean NOT NULL DEFAULT false,
    reason varchar(500),
    requested_by_oid uuid NOT NULL,
    idempotency_key varchar(64),
    preview_expires_at bigint NOT NULL,
    created_at bigint NOT NULL,
    started_at bigint,
    finished_at bigint,
    locked_by varchar(64),
    locked_until bigint,
    CONSTRAINT staff_rollback_job_pkey PRIMARY KEY (id),
    CONSTRAINT staff_rollback_job_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT staff_rollback_job_state_check CHECK (state IN ('PREVIEW', 'RUNNING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT staff_rollback_job_selector_check CHECK ((selector_oid IS NULL) <> (selector_minecraft_uuid IS NULL)),
    CONSTRAINT staff_rollback_job_window_check CHECK (window_from <= window_to)
);

CREATE INDEX staff_rollback_job_state_idx ON staff_rollback_job (state);

-- One row per frozen punishment. The primary key makes a repeated freeze of the same punishment in the
-- same job impossible. The item keeps no reason text and no player name; owner is the hashed owner.
CREATE TABLE staff_rollback_item (
    job_id uuid NOT NULL,
    punishment_identifier varchar(255) NOT NULL,
    owner varchar(255) NOT NULL,
    section_key varchar(64) NOT NULL,
    kind varchar(16) NOT NULL,
    state varchar(16) NOT NULL,
    reason_code varchar(32),
    processed_at bigint,
    CONSTRAINT staff_rollback_item_pkey PRIMARY KEY (job_id, punishment_identifier),
    CONSTRAINT staff_rollback_item_job_fkey FOREIGN KEY (job_id) REFERENCES staff_rollback_job (id),
    CONSTRAINT staff_rollback_item_state_check CHECK (state IN ('PENDING', 'REVOKED', 'SKIPPED', 'FAILED'))
);
