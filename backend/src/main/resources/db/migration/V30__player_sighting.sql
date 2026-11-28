-- One row per login attempt and session, reported by Velocity through /intel/sessions. The clear IP and the raw
-- player UUID are stored on purpose (openspec add-player-intelligence, decisions 3 and 10); IntelRetentionSweeper
-- deletes rows after blackhole.intel.retention-days. Times are epoch milliseconds, as in kick_action.
CREATE TABLE player_sighting (
    session_id uuid NOT NULL,
    owner_hash char(128) NOT NULL,
    player_uuid uuid NOT NULL,
    player_name varchar(16) NOT NULL,
    ip varchar(45) NOT NULL,
    ip_token char(128),
    asn bigint,
    as_org varchar(128),
    country_code char(2),
    client_brand varchar(64),
    client_version varchar(64),
    protocol_version integer,
    virtual_host varchar(255),
    outcome varchar(16) NOT NULL,
    started_at bigint NOT NULL,
    ended_at bigint,
    created_at bigint NOT NULL,
    CONSTRAINT pk_player_sighting PRIMARY KEY (session_id),
    CONSTRAINT ck_player_sighting_outcome CHECK (outcome IN ('ALLOWED', 'DENIED'))
);
CREATE INDEX idx_player_sighting_owner ON player_sighting (owner_hash);
CREATE INDEX idx_player_sighting_uuid ON player_sighting (player_uuid);
CREATE INDEX idx_player_sighting_ip ON player_sighting (ip);
CREATE INDEX idx_player_sighting_ip_token ON player_sighting (ip_token);
CREATE INDEX idx_player_sighting_started ON player_sighting (started_at);
