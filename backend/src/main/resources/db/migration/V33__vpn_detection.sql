-- VPN and hosting detection (openspec add-vpn-proxy-detection, design migration plan). Additive only: new tables, no
-- existing table changes. The list generation pointer is a row (vpn_list_state) and not a transaction, because
-- @Transactional is unusable in this codebase: a refresh writes a new generation, then moves the pointer in one statement.
-- Times are epoch milliseconds, as in player_sighting.

-- One row per enabled list source: refresh status for the panel. The URLs and kinds stay in configuration.
CREATE TABLE vpn_list_source (
    source_key varchar(64) NOT NULL,
    last_attempt_at bigint,
    last_success_at bigint,
    last_error varchar(255),
    entry_count bigint NOT NULL DEFAULT 0,
    invalid_count bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_vpn_list_source PRIMARY KEY (source_key)
);

-- Every generation being written or kept. created_at lets the sweeper tell an orphan from a refresh in progress.
CREATE TABLE vpn_list_generation (
    source_key varchar(64) NOT NULL,
    generation bigint NOT NULL,
    created_at bigint NOT NULL,
    CONSTRAINT pk_vpn_list_generation PRIMARY KEY (source_key, generation)
);
CREATE INDEX ix_vpn_list_generation_created ON vpn_list_generation (created_at);

-- The active generation per source. Readers join on it; a refresh switches it with one upsert.
CREATE TABLE vpn_list_state (
    source_key varchar(64) NOT NULL,
    active_generation bigint NOT NULL,
    activated_at bigint NOT NULL,
    CONSTRAINT pk_vpn_list_state PRIMARY KEY (source_key)
);

-- One canonical CIDR per row. cidr is text, as stored by IntelPrefix: IPv4 or IPv6 network with its length.
CREATE TABLE vpn_list_entry (
    source_key varchar(64) NOT NULL,
    generation bigint NOT NULL,
    cidr varchar(49) NOT NULL,
    CONSTRAINT pk_vpn_list_entry PRIMARY KEY (source_key, generation, cidr)
);

-- One policy per classification. The three rows are seeded at FLAG: the safe default, DENY is an explicit admin choice.
CREATE TABLE vpn_policy (
    classification varchar(16) NOT NULL,
    policy varchar(8) NOT NULL,
    updated_at bigint NOT NULL,
    CONSTRAINT pk_vpn_policy PRIMARY KEY (classification),
    CONSTRAINT ck_vpn_policy_classification CHECK (classification IN ('VPN', 'HOSTING', 'PROXY')),
    CONSTRAINT ck_vpn_policy_value CHECK (policy IN ('ALLOW', 'FLAG', 'DENY'))
);
INSERT INTO vpn_policy (classification, policy, updated_at) VALUES
    ('VPN', 'FLAG', 0),
    ('HOSTING', 'FLAG', 0),
    ('PROXY', 'FLAG', 0);

-- Per-player exemptions, keyed by the owner hash only: no name and no address is stored with an entry.
CREATE TABLE vpn_allow_list (
    owner_hash char(128) NOT NULL,
    reason varchar(200) NOT NULL,
    actor_oid varchar(64) NOT NULL,
    expires_at bigint,
    created_at bigint NOT NULL,
    CONSTRAINT pk_vpn_allow_list PRIMARY KEY (owner_hash),
    CONSTRAINT ck_vpn_allow_list_reason CHECK (char_length(reason) BETWEEN 1 AND 200)
);

-- One row per login verdict other than a plain ALLOW, and per allow-listed match. Holds the clear address for review,
-- so it is kept only for blackhole.intel.hit-retention. classification lists every matching class, comma-separated.
CREATE TABLE vpn_detection_hit (
    id uuid NOT NULL,
    owner_hash char(128) NOT NULL,
    ip varchar(45) NOT NULL,
    classification varchar(64) NOT NULL,
    source_key varchar(160) NOT NULL,
    asn bigint,
    organisation varchar(128),
    verdict varchar(8) NOT NULL,
    allow_listed boolean NOT NULL DEFAULT false,
    created_at bigint NOT NULL,
    CONSTRAINT pk_vpn_detection_hit PRIMARY KEY (id),
    CONSTRAINT ck_vpn_detection_hit_verdict CHECK (verdict IN ('ALLOW', 'FLAG', 'DENY'))
);
CREATE INDEX ix_vpn_detection_hit_owner ON vpn_detection_hit (owner_hash, created_at);
CREATE INDEX ix_vpn_detection_hit_created ON vpn_detection_hit (created_at);
