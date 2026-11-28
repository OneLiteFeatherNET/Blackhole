-- The inbox's changed-by-others section first finds the punishments with a case event at or after a time (occurred_at).
-- Without an index on that column every inbox read scans case_event. The composite lets the lookup be answered from the
-- index alone. Append-only: no earlier migration is changed.
CREATE INDEX idx_case_event_occurred ON case_event (occurred_at, punishment_identifier);
