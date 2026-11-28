-- One row per owner and name (matched without case), written at each login by SessionService and read by the
-- old-name lookup (openspec add-name-history, decisions 1 to 3). The raw player UUID is stored on purpose
-- (add-player-intelligence decision 10); NameHistorySweeper deletes rows after blackhole.intel.name-retention-days.
-- Times are epoch milliseconds, as in player_sighting.
CREATE TABLE player_name_history (
    owner_hash char(128) NOT NULL,
    player_uuid uuid NOT NULL,
    name_lower varchar(16) NOT NULL,
    name varchar(16) NOT NULL,
    first_seen bigint NOT NULL,
    last_seen bigint NOT NULL,
    CONSTRAINT pk_player_name_history PRIMARY KEY (owner_hash, name_lower)
);
CREATE INDEX idx_player_name_history_name ON player_name_history (name_lower);
CREATE INDEX idx_player_name_history_last_seen ON player_name_history (last_seen);

-- One-time backfill from the sightings that exist when this migration runs. The newest casing and the UUID come from
-- the newest sighting of each name; names already removed by the session sweep are not reconstructed.
WITH names AS (
    SELECT owner_hash, lower(player_name) AS name_lower, min(started_at) AS first_seen, max(started_at) AS last_seen
    FROM player_sighting
    GROUP BY owner_hash, lower(player_name)
)
INSERT INTO player_name_history (owner_hash, player_uuid, name_lower, name, first_seen, last_seen)
SELECT n.owner_hash, latest.player_uuid, n.name_lower, latest.player_name, n.first_seen, n.last_seen
FROM names n
CROSS JOIN LATERAL (
    SELECT s.player_uuid, s.player_name
    FROM player_sighting s
    WHERE s.owner_hash = n.owner_hash AND lower(s.player_name) = n.name_lower
    ORDER BY s.started_at DESC, s.session_id
    LIMIT 1
) latest;
