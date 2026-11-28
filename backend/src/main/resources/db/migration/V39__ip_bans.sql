-- IP bans (openspec add-ip-bans-via-firewall, design decision 1). Additive only: one new table. The clear address is
-- stored on purpose, because the firewall alias and the proxy compare addresses (amended data protection principle).
-- Times are epoch milliseconds, as in player_sighting. A lifted row keeps its history until the retention sweep.
CREATE TABLE ip_bans (
    id uuid NOT NULL,
    network varchar(50) NOT NULL,
    family smallint NOT NULL,
    prefix_length smallint NOT NULL,
    reason varchar(200) NOT NULL,
    template_key varchar(64),
    source varchar(16) NOT NULL,
    punishment_id uuid,
    case_id uuid,
    actor_oid uuid,
    created_at bigint NOT NULL,
    expires_at bigint,
    lifted_at bigint,
    lifted_by_oid uuid,
    lift_reason varchar(200),
    import_key varchar(64),
    CONSTRAINT pk_ip_bans PRIMARY KEY (id),
    CONSTRAINT ck_ip_bans_family CHECK (family IN (4, 6)),
    CONSTRAINT ck_ip_bans_source CHECK (source IN ('PANEL', 'COMMAND', 'PUNISHMENT', 'IMPORT')),
    CONSTRAINT uk_ip_bans_import_key UNIQUE (import_key)
);

-- At most one active ban per canonical range.
CREATE UNIQUE INDEX ux_ip_bans_active_network ON ip_bans (network) WHERE lifted_at IS NULL;
CREATE INDEX ix_ip_bans_closed ON ip_bans (lifted_at, expires_at);
