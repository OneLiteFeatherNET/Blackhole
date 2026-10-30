-- The editable unattended settings of the join guard (openspec configure-join-guard-in-panel, decisions 2 and 3).
-- Additive only: one new table, no existing table changes. There is deliberately no seed row: the application inserts
-- the row from blackhole.guard.unattended.* on the first read, because Flyway cannot read application.yml.

-- One row. The window times are minutes after midnight, so 01:00 is 60 and 23:59 is 1439. The window is end exclusive
-- and may cross midnight; its start and end may not be equal. updated_by_oid is NULL while the row is the seeded
-- configuration.
CREATE TABLE join_guard_settings (
    id smallint NOT NULL,
    unattended_level varchar(8) NOT NULL,
    absence_threshold_minutes integer NOT NULL,
    night_window_enabled boolean NOT NULL,
    night_window_start_minute smallint NOT NULL,
    night_window_end_minute smallint NOT NULL,
    time_zone varchar(64) NOT NULL,
    updated_at bigint NOT NULL,
    updated_by_oid varchar(64),
    CONSTRAINT pk_join_guard_settings PRIMARY KEY (id),
    CONSTRAINT ck_join_guard_settings_single CHECK (id = 1),
    CONSTRAINT ck_join_guard_settings_level CHECK (unattended_level IN ('STRICT', 'LOCKDOWN')),
    CONSTRAINT ck_join_guard_settings_threshold CHECK (absence_threshold_minutes BETWEEN 5 AND 240),
    CONSTRAINT ck_join_guard_settings_start CHECK (night_window_start_minute BETWEEN 0 AND 1439),
    CONSTRAINT ck_join_guard_settings_end CHECK (night_window_end_minute BETWEEN 0 AND 1439),
    CONSTRAINT ck_join_guard_settings_window CHECK (night_window_start_minute <> night_window_end_minute)
);
