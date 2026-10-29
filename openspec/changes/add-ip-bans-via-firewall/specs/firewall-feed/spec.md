## ADDED Requirements

### Requirement: Two pulled feeds
The backend SHALL serve two plain-text feeds for the firewall, `GET /firewall/feed/{token}/ips` and `GET /firewall/feed/{token}/networks`, whose content is set by the requirements that follow. The firewall fetches them; the backend MUST NOT call the firewall, and MUST NOT hold any firewall credential.

#### Scenario: Single addresses feed
- **WHEN** the firewall fetches `/ips` while the IP bans `203.0.113.7/32` and `2001:db8::7/128` are active
- **THEN** the body contains the bare addresses `203.0.113.7` and `2001:db8::7`, one per line, IPv4 before IPv6

#### Scenario: Networks feed
- **WHEN** the firewall fetches `/networks` while the IP ban `203.0.113.0/24` is active
- **THEN** the body contains the line `203.0.113.0/24`

#### Scenario: No firewall call
- **WHEN** an IP ban is created
- **THEN** the backend sends no request to any firewall

### Requirement: Networks feed content
The networks feed SHALL include the VPN, hosting and proxy ranges of every class listed in `blackhole.firewall.feed.vpn-classes` (default `VPN,HOSTING,PROXY`) whose policy is `DENY`, and SHALL include the networks of active ASN area bans. The networks of the configured hosting ASNs are included only while the HOSTING policy is `DENY`, the same rule as the list classes. A class whose policy is `ALLOW` or `FLAG` contributes nothing. Country area bans MUST NOT appear in any feed.

#### Scenario: Proxy class set to DENY
- **WHEN** the proxy class has the policy `DENY` and the policy of the other classes is `FLAG`
- **THEN** the networks feed contains the proxy ranges and no VPN or hosting range from the lists

#### Scenario: Class set to FLAG
- **WHEN** the hosting class has the policy `FLAG`
- **THEN** its ranges and the networks of the configured hosting ASNs are absent from the networks feed

#### Scenario: Hosting ASN under DENY
- **WHEN** the hosting policy is `DENY` and AS64501 is a configured hosting ASN
- **THEN** the networks of AS64501 appear in the networks feed

#### Scenario: ASN area ban
- **WHEN** an active ASN area ban for AS64500 exists
- **THEN** the networks feed contains the networks of AS64500 from the offline ASN database

#### Scenario: Country area ban
- **WHEN** an active country area ban for a country code exists
- **THEN** no line of that country's networks appears in any feed

### Requirement: Feed format
Each feed SHALL be served as `text/plain; charset=utf-8` with LF line endings and ASCII content, one entry per line, sorted (IPv4 before IPv6, then by address), with each network written once. The same data MUST always produce the same bytes.

#### Scenario: Same data twice
- **WHEN** the feed is built twice without a change in the data
- **THEN** both bodies are byte-identical

#### Scenario: Network named twice
- **WHEN** an IP ban and a VPN range name the same network
- **THEN** the network appears once in the networks feed

### Requirement: Protected networks never reach a feed
The feed renderer MUST refuse to write any line that overlaps a protected network, even when the database holds one, and MUST count each refusal in `blackhole.ipban.feed.refused`.

#### Scenario: Edge address in the database
- **WHEN** the database holds an active row that overlaps a protected network
- **THEN** the line is absent from the feed and `blackhole.ipban.feed.refused` increases

### Requirement: Optional comment lines
A feed SHALL write an optional comment line `# ` before an entry, carrying the ban identifier, the kind or category and the expiry (`never` when there is none). A comment MUST NOT carry a player name, an owner hash, a reason text or an address other than the entry it precedes. The query `?comments=false` SHALL produce the same entries with no comment line.

#### Scenario: Comment for a ban
- **WHEN** the feed is fetched with comments and the ban `5f2c...` of kind `PANEL` expires at `2026-11-05T12:00:00Z`
- **THEN** the line before its entry is `# id=5f2c... kind=PANEL expires=2026-11-05T12:00:00Z`

#### Scenario: Comment-free mode
- **WHEN** the feed is fetched with `?comments=false`
- **THEN** no line of the body starts with `#`, and the entries are the same as with comments

#### Scenario: No names in comments
- **WHEN** the banned player's name and a reason text exist for the ban
- **THEN** neither appears in any comment line

### Requirement: Feed tokens
Each firewall client SHALL hold one random token of at least 32 bytes, encoded as base64url without padding. The backend SHALL store only the SHA-256 hash of each token, and SHALL compare a presented token with the stored hashes in constant time. A token MUST NOT be logged, returned or placed in an event.

#### Scenario: Token is stored as a hash
- **WHEN** a client is configured with its token
- **THEN** the secrets directory holds the SHA-256 hex of the token and not the token

#### Scenario: Token comparison
- **WHEN** a request presents a token
- **THEN** the backend compares its hash with every configured hash in constant time, without stopping at the first match

### Requirement: Feed client configuration
Clients are named in `blackhole.firewall.feed.clients` (`BLACKHOLE_FIREWALL_FEED_CLIENTS`), and each name MUST match `[a-z0-9-]{1,32}`. Each name has one hash file in `blackhole.firewall.feed.secrets-dir`, read once at start-up. A second hash file `<name>.next` lets a token rotate: both tokens are accepted until the old file is removed.

#### Scenario: Rotation with two tokens
- **WHEN** a second hash file `<name>.next` is added for a client and the backend is restarted
- **THEN** both tokens are accepted until the old hash file is removed

#### Scenario: No clients configured
- **WHEN** `blackhole.firewall.feed.clients` is empty
- **THEN** every feed route answers 404

#### Scenario: Malformed hash file
- **WHEN** a client's hash file is missing or malformed outside `dev` and `test`
- **THEN** the backend refuses to start

### Requirement: Unknown token or source
An unknown token, an unknown path and a source address outside `blackhole.firewall.feed.allowed-sources` SHALL each return HTTP 404 with an empty body, so that the responses do not reveal which check failed. The allow-list takes IP addresses and CIDR ranges only; a host name MUST be refused at start-up.

#### Scenario: Unknown token
- **WHEN** a request presents a token that matches no client
- **THEN** the response is HTTP 404 with an empty body

#### Scenario: Source outside the allow-list
- **WHEN** a request with a valid token comes from an address outside the allow-list
- **THEN** the response is HTTP 404 with an empty body, the same as for an unknown token

#### Scenario: Host name in the allow-list
- **WHEN** the allow-list contains a host name
- **THEN** the backend refuses to start

### Requirement: HTTPS only
The feed routes MUST be reachable only over TLS. The ingress MUST reject plain HTTP on the feed path, and the backend MUST NOT redirect a plain HTTP request, because a redirect would send the token in clear text first.

#### Scenario: Plain HTTP request
- **WHEN** a feed is requested over plain HTTP
- **THEN** the request does not reach the backend and no feed content is served over HTTP

### Requirement: Polling with ETag
Each feed response SHALL carry a strong `ETag` of the exact body and `Cache-Control: no-cache`. When `If-None-Match` matches the current ETag, the backend SHALL answer `304 Not Modified` with no body. The comment mode and the comment-free mode have separate ETags.

#### Scenario: Nothing changed
- **WHEN** the firewall fetches a feed with the ETag of the current content
- **THEN** the response is HTTP 304 with no body

#### Scenario: Content changed
- **WHEN** the firewall fetches a feed with an ETag that no longer matches
- **THEN** the response is HTTP 200 with the new body and a new ETag

#### Scenario: Revalidation
- **WHEN** a feed is served
- **THEN** the response carries `Cache-Control: no-cache`

### Requirement: Feed served from memory
The backend SHALL build each feed in memory. It MUST rebuild a feed only when IP bans, the VPN or hosting lists or policies, the ASN area bans or the ASN database changed, and it MUST rebuild at least every `blackhole.firewall.feed.rebuild-interval` (default `PT60S`) on a background schedule. A request MUST NOT read the database for a feed that is already built.

#### Scenario: Repeated fetches
- **WHEN** the firewall fetches a built feed twice
- **THEN** neither request reads an IP ban row from the database

#### Scenario: Ban changed
- **WHEN** an IP ban is lifted
- **THEN** the next feed build no longer contains its entry

#### Scenario: Lost change event
- **WHEN** a change event is not received
- **THEN** the scheduled rebuild corrects the feed within `blackhole.firewall.feed.rebuild-interval`

### Requirement: Errors never empty a feed
A failed build MUST keep the last good feed. An empty feed MUST NOT be produced because of an error. Before the first successful build, the route SHALL answer HTTP 503 with an empty body. Concurrent first requests SHALL trigger one build.

#### Scenario: Database error during a rebuild
- **WHEN** a rebuild fails after a good feed exists
- **THEN** the previous feed is served and `blackhole.ipban.feed.build_errors` increases

#### Scenario: First request before any build
- **WHEN** a feed is requested before the first successful build
- **THEN** the response is HTTP 503 with an empty body, and the firewall keeps its own table

### Requirement: Template-only logging
Request logs, metrics and traces for a feed SHALL name only the route template `/firewall/feed/{token}/{feed}`. The token and the raw path MUST NOT appear in any log line, metric tag or trace attribute written for the feed.

#### Scenario: Logged request
- **WHEN** a feed is requested with a valid token
- **THEN** the access log and the metric tags contain the route template and not the token

#### Scenario: Metrics by outcome
- **WHEN** a feed is requested
- **THEN** `blackhole.ipban.feed.requests` increases with the tags `feed`, `client` and `outcome`

### Requirement: Feed health without a firewall probe
The backend SHALL expose `blackhole.ipban.feed.last_fetch_seconds` per client, the time since that client last received a `200` or a `304`. The backend MUST NOT probe the firewall, and it MUST NOT report the firewall as a health failure.

#### Scenario: Firewall stops fetching
- **WHEN** no fetch from a client has succeeded for a long time
- **THEN** `blackhole.ipban.feed.last_fetch_seconds` for that client grows, and the application keeps serving requests
