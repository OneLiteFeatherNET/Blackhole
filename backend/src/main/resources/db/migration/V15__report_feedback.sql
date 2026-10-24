-- Report feedback (complete-player-reports). When a report is resolved as ACTIONED or DISMISSED the
-- reporter's proxy is told, at once if the reporter is online and otherwise at their next login. A row
-- is the pending message: the reporter's owner hash (never a raw UUID), the report and its category,
-- the outcome (reports.status ordinal: 2 ACTIONED, 3 DISMISSED) and an expiry. It never holds the
-- punishment or the resolver. The proxy deletes the row by acknowledging it; the sweeper deletes the
-- rest once expires_at has passed, which also bounds how long the reporter's hash is kept here.
CREATE TABLE report_feedback (
    identifier uuid NOT NULL,
    reporter_hash varchar(128) NOT NULL,
    report_identifier uuid NOT NULL,
    category smallint NOT NULL,
    outcome smallint NOT NULL,
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT report_feedback_pkey PRIMARY KEY (identifier),
    CONSTRAINT report_feedback_outcome_check CHECK (outcome IN (2, 3))
);
CREATE INDEX report_feedback_reporter_hash_idx ON report_feedback (reporter_hash);
CREATE INDEX report_feedback_expires_at_idx ON report_feedback (expires_at);
