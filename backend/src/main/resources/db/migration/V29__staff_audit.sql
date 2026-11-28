-- Append-only record of staff actions. No foreign keys: retention and anonymization never block history.
CREATE TABLE staff_audit (
    seq             BIGINT GENERATED ALWAYS AS IDENTITY,
    identifier      UUID         PRIMARY KEY,
    occurred_at     BIGINT       NOT NULL,
    actor_oid       UUID         NOT NULL,
    actor_name      VARCHAR(256),
    surface         VARCHAR(16)  NOT NULL,
    action          VARCHAR(48)  NOT NULL,
    target_kind     VARCHAR(16)  NOT NULL,
    target_ref      VARCHAR(255),
    target_name     VARCHAR(64),
    detail          VARCHAR(200),
    recorded_by_oid UUID         NOT NULL
);

CREATE INDEX staff_audit_occurred_idx ON staff_audit (occurred_at, seq);
CREATE INDEX staff_audit_actor_idx ON staff_audit (actor_oid, seq);
CREATE INDEX staff_audit_action_idx ON staff_audit (action, seq);
