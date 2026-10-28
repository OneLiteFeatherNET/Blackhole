## ADDED Requirements

### Requirement: Offline classification of login addresses
The system SHALL classify a login address using only offline data: the configured hosting ASN list, the enabled list sources and the ASN and organisation held from sightings. The classification MUST NOT call any external service per login.

#### Scenario: Hosting ASN
- **WHEN** a login address belongs to an ASN in `blackhole.intel.hosting-asns`
- **THEN** it is classified `HOSTING` without any network request

#### Scenario: Listed VPN range
- **WHEN** a login address lies inside a range of an enabled `VPN` list source
- **THEN** it is classified `VPN` with that source key

#### Scenario: Unlisted address
- **WHEN** a login address matches no hosting ASN and no enabled list source
- **THEN** it is classified `CLEAN` and no hit is recorded

#### Scenario: Several matches
- **WHEN** an address matches a `VPN` list and a hosting ASN
- **THEN** both classes are recorded on the hit and the stronger policy applies

### Requirement: Configured list sources only
The system SHALL read list sources only from configuration (`blackhole.intel.lists`). The panel MUST NOT be able to add or change a list URL. Each source SHALL declare its key, kind (`VPN`, `HOSTING` or `PROXY`), URL, enabled flag and attribution.

#### Scenario: Default X4BNet sources
- **WHEN** the backend starts with default configuration
- **THEN** the two X4BNet sources exist and are disabled until an admin enables them

### Requirement: Refresh of list sources
The system SHALL refresh each enabled list source every `blackhole.intel.lists.refresh-interval` and on an admin's refresh request, at most once per source per 10 minutes. A refresh MUST replace the source's content as one generation: the previous generation stays active until every entry of the new one is written and the count is plausible. A failed refresh MUST keep the previous generation and record the error.

#### Scenario: Successful refresh
- **WHEN** a refresh of a source completes with valid entries
- **THEN** the new generation becomes active and the previous one is removed

#### Scenario: Failed refresh
- **WHEN** a refresh fails with a timeout
- **THEN** the previous generation stays active and the source shows the failure with its time

#### Scenario: Too many invalid lines
- **WHEN** more than 5% of the lines of a downloaded list are not valid CIDR ranges
- **THEN** the refresh fails and the previous generation stays active

### Requirement: Hardened list download
The system MUST fetch list sources only over HTTPS, MUST refuse addresses that resolve to loopback, link-local, private, unique-local or metadata ranges, MUST follow redirects only to the same host, MUST cap the body at 20 MB, and MUST apply connect and read timeouts.

#### Scenario: Plain HTTP source
- **WHEN** a configured source uses `http://`
- **THEN** the source is refused at start-up and the health indicator reports it

#### Scenario: Redirect to another host
- **WHEN** a source answers with a redirect to a different host
- **THEN** the redirect is not followed and the refresh fails

### Requirement: Per-class policy
The system SHALL hold one policy per classification (`VPN`, `HOSTING`, `PROXY`) with the value `ALLOW`, `FLAG` or `DENY`. All three SHALL default to `FLAG`. Setting a policy to `DENY` MUST require the `ADMIN` role and MUST record the previous value.

#### Scenario: Default policy
- **WHEN** the system starts for the first time
- **THEN** all three policies are `FLAG`

#### Scenario: Moderator sets DENY
- **WHEN** a moderator sets a policy to `DENY`
- **THEN** the response is HTTP 403 and the policy is unchanged

#### Scenario: Admin sets DENY
- **WHEN** an admin sets the `VPN` policy to `DENY`
- **THEN** the policy is `DENY` and the change records the previous value

### Requirement: Login verdict
The system SHALL answer `POST /intel/login-check` (role `PROXY`) with a verdict of `ALLOW`, `FLAG` or `DENY` for the owner hash and address given. An owner on the allow-list MUST receive `ALLOW` for every class. The verdict MUST be computed from the in-memory structures without a database read.

#### Scenario: Denied class
- **WHEN** the address is `VPN` and the `VPN` policy is `DENY`
- **THEN** the verdict is `DENY`

#### Scenario: Flagged class
- **WHEN** the address is `HOSTING` and the `HOSTING` policy is `FLAG`
- **THEN** the verdict is `FLAG` and a hit is recorded

#### Scenario: Allow-listed owner
- **WHEN** an allow-listed owner logs in from a `VPN` address with the `VPN` policy at `DENY`
- **THEN** the verdict is `ALLOW` and a hit is recorded with `allow_listed` set

#### Scenario: Clean address
- **WHEN** the address is `CLEAN`
- **THEN** the verdict is `ALLOW` and no hit is recorded

### Requirement: Fail-open login check
The proxy SHALL allow a login when the login check times out, fails or returns an unknown verdict, and SHALL count each such case. A broken backend MUST NOT lock players out.

#### Scenario: Backend slow
- **WHEN** the login check does not answer within `blackhole.intel.login-check.timeout`
- **THEN** the login is allowed and `blackhole.intel.login_check.failopen` increases

### Requirement: Per-player allow-list
The system SHALL keep an allow-list of owner hashes, each with a reason of 1 to 200 characters, the actor's `oid`, creation time and an optional expiry. A MODERATOR MAY add an entry expiring within 90 days; only an ADMIN MAY add a permanent entry. The allow-list MUST NOT store a name or an address.

#### Scenario: Moderator temporary exemption
- **WHEN** a moderator exempts a player for 30 days with a reason
- **THEN** the entry is active for 30 days

#### Scenario: Moderator permanent exemption
- **WHEN** a moderator asks for a permanent exemption
- **THEN** the response is HTTP 403 with the code `FORBIDDEN_DURATION`

#### Scenario: Expired exemption
- **WHEN** an exemption has expired
- **THEN** it no longer produces `ALLOW` for every class

### Requirement: Hits
The system SHALL record a hit for each login verdict other than `ALLOW` and for each allow-listed match, holding the owner hash, the address, the classification, the source key, the ASN and organisation when known, the verdict and the time. Hits SHALL be visible to `ADMIN`, `MODERATOR` and `VIEWER` only, and MUST NOT be published or sent to any third party.

#### Scenario: Flag produces a hit
- **WHEN** a flagged login is checked
- **THEN** one hit with the verdict `FLAG` exists for the owner

#### Scenario: Hits on the player page
- **WHEN** staff open a player page
- **THEN** the hits of that owner are listed with class, source and verdict

### Requirement: Retention of hits
The system SHALL delete hits older than `blackhole.intel.hit-retention` (default `P90D`), reading an injected `Clock`.

#### Scenario: Old hit
- **WHEN** the retention sweep runs and a hit is older than the retention period
- **THEN** the hit is deleted

### Requirement: Hits never create punishments
A hit or a verdict MUST NOT create, change or lift any punishment or IP ban.

#### Scenario: Denied login
- **WHEN** a login is denied by policy
- **THEN** no punishment is created for the player

### Requirement: Staff notice for flagged logins
The system SHALL publish `blackhole.events.intel.vpn.flagged` for a flagged login with the hit identifier and the classification only. Notices for one owner SHALL be aggregated over 30 minutes. The event MUST NOT carry the address or the owner hash.

#### Scenario: Reconnect loop
- **WHEN** the same owner is flagged five times within 30 minutes
- **THEN** one notice is published for that window

#### Scenario: Event content
- **WHEN** a flagged notice is published
- **THEN** it contains the hit identifier and the class and no address

### Requirement: Attribution of list sources
The panel SHALL show, for each list source, its attribution text and licence. Blackhole MUST NOT redistribute list content; it downloads it from the configured URL at refresh time.

#### Scenario: X4BNet attribution
- **WHEN** an admin opens the list sources
- **THEN** the X4BNet source shows its MIT licence and copyright line

### Requirement: Health and metrics of classification
The system SHALL expose the counters and gauges of `design.md` decision 8 and the `intel-lists` health indicator, which reports DOWN only when every enabled source has failed for more than two refresh intervals. A DOWN indicator MUST NOT block logins.

#### Scenario: One source failing
- **WHEN** one of two enabled sources fails
- **THEN** the health indicator stays UP and the classifier keeps the last good generation of the failed source
