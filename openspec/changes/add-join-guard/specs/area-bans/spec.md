## ADDED Requirements

### Requirement: Area ban record
An area ban SHALL cover one autonomous system number (`ASN`) or one country code (`COUNTRY`, ISO 3166 alpha-2). It SHALL hold a reason of 1 to 200 characters, a source (`MANUAL` or `AUTO_BURST`), the actor's `oid` (NULL for automatic and Discord bans), the creation time and an optional expiry.

#### Scenario: Recorded fields
- **WHEN** an ADMIN creates an ASN ban with a reason and no expiry
- **THEN** the record holds the kind, value, reason, source `MANUAL`, the admin's `oid` and no expiry

### Requirement: Area ban keys
The ASN SHALL be stored as digits without an `AS` prefix, the country code in uppercase, and at most one active area ban SHALL exist per kind and value.

#### Scenario: Normalised ASN
- **WHEN** a staff member creates an ASN ban for `AS16276`
- **THEN** the stored value is `16276`

#### Scenario: Second active ban
- **WHEN** an active ban already exists for ASN 16276 and a staff member creates another one
- **THEN** the request is refused with `ALREADY_ACTIVE` and the existing ban is unchanged

#### Scenario: Invalid country code
- **WHEN** a staff member creates a country ban for `DEU`
- **THEN** the request is refused as invalid and nothing is stored

### Requirement: Who may create area bans
A MODERATOR SHALL be able to create an ASN ban with an expiry of at most 7 days, and SHALL NOT be able to create a country ban or an ASN ban that is permanent or longer than 7 days. An ADMIN SHALL be able to create any area ban. Every creation SHALL require a reason.

#### Scenario: Moderator with a seven-day ASN ban
- **WHEN** a MODERATOR creates an ASN ban that expires in 7 days
- **THEN** the ban is created

#### Scenario: Moderator with a permanent ASN ban
- **WHEN** a MODERATOR creates an ASN ban without an expiry
- **THEN** the request is refused with `FORBIDDEN_DURATION`

#### Scenario: Moderator with a country ban
- **WHEN** a MODERATOR creates a country ban
- **THEN** the request is refused with `FORBIDDEN_KIND`

#### Scenario: Admin with a country ban
- **WHEN** an ADMIN creates a country ban for `RU` with an expiry of 24 hours
- **THEN** the ban is created and expires after 24 hours

### Requirement: Area ban enforcement in the login check
The login check SHALL refuse a login with `denialReason = AREA_BAN` when the address's ASN or country matches an active area ban. An area ban is active when it is not lifted and its expiry, if any, is in the future according to the injected clock. The refusal SHALL apply to every owner except linked staff, including an owner on the VPN allow-list.

#### Scenario: Provider banned
- **WHEN** ASN 16276 is actively banned and a player logs in from an address of ASN 16276
- **THEN** the login is refused with `denialReason = AREA_BAN`

#### Scenario: Expired area ban
- **WHEN** an ASN ban expired 1 minute ago and a player logs in from that ASN
- **THEN** the login is not refused by the area ban

#### Scenario: Allow-listed player on a banned provider
- **WHEN** an owner on the VPN allow-list logs in from an ASN with an active area ban
- **THEN** the login is refused with `denialReason = AREA_BAN`

#### Scenario: Linked staff on a banned provider
- **WHEN** a linked staff member logs in from an ASN with an active area ban
- **THEN** the login is admitted

### Requirement: Lifting an area ban
Lifting an area ban SHALL require a reason of 1 to 200 characters and SHALL record the lift time and the lifting actor. A lifted ban SHALL no longer apply at the next login check. Lifting SHALL NOT change any other ban. The history of lifted and expired bans SHALL remain readable.

#### Scenario: Lift without a reason
- **WHEN** a staff member lifts an area ban without a reason
- **THEN** the request is refused and the ban stays active

#### Scenario: Lift takes effect at the next login
- **WHEN** an area ban for ASN 16276 is lifted and a player then logs in from that ASN
- **THEN** the login is not refused by the area ban

### Requirement: In-game area ban command
The proxy SHALL offer `/areaban asn <number> [duration] <reason>`, `/areaban country <ISO> [duration] <reason>` and `/areaban lift asn|country <value> <reason>`, guarded by the permission node `blackhole.areaban`. A country ban SHALL additionally require `blackhole.areaban.country`. A command without a reason SHALL be refused with its usage text. The command SHALL call the backend once and report the result to the sender.

#### Scenario: Missing reason
- **WHEN** a staff member runs `/areaban asn 16276 2h`
- **THEN** the command shows its usage text and creates nothing

#### Scenario: Country ban without the country permission
- **WHEN** a moderator without `blackhole.areaban.country` runs `/areaban country DE 1h test`
- **THEN** the command is refused and no backend call is made

### Requirement: Burst rule creates automatic area bans
When `blackhole.guard.burst.enabled` is true and the count of guard refusals per ASN in the sliding window reaches `blackhole.guard.burst.threshold` (default 10), the backend SHALL create an area ban for that ASN with source `AUTO_BURST`, no actor and an expiry of `blackhole.guard.burst.ban-duration` (default 2 hours), and SHALL publish `area_ban.created` at once. No second ban SHALL be created while one is active.

#### Scenario: Burst from one provider
- **WHEN** the rule is enabled and 10 guard refusals from ASN 16276 occur within the 5-minute window
- **THEN** an `AUTO_BURST` area ban for ASN 16276 is created with an expiry 2 hours later, and `area_ban.created` is published

### Requirement: Burst rule scope
The burst rule SHALL count only guard refusals, measured in a sliding window of `blackhole.guard.burst.window` (default 5 minutes). Refusals by area bans and by the VPN policy SHALL NOT be counted. When the rule is disabled, nothing SHALL be counted or created.

#### Scenario: Spread-out refusals
- **WHEN** the rule is enabled and 10 guard refusals from ASN 16276 occur over 6 minutes
- **THEN** no area ban is created

#### Scenario: Rule disabled
- **WHEN** the rule is disabled and 100 guard refusals occur from one ASN
- **THEN** no area ban is created and nothing is counted

#### Scenario: Ban already active
- **WHEN** an active ban exists for ASN 16276 and another burst is detected for it
- **THEN** no second ban is created and no event is published

#### Scenario: Automatic ban expires on its own
- **WHEN** an `AUTO_BURST` ban reaches its expiry
- **THEN** logins from that ASN are no longer refused by it, without any staff action

### Requirement: Area ban alerts
Creating or lifting an area ban SHALL publish `area_ban.created` or `area_ban.lifted`. The staff Discord channel SHALL receive these events through the existing notification path. The payload SHALL carry the kind, the value, the source, the expiry, the reason and the actor's display name when the actor is linked, and SHALL NOT carry an IP address, an owner hash or a player name.

#### Scenario: Automatic ban is marked
- **WHEN** a burst ban is created
- **THEN** the Discord message names the source as automatic and states the expiry

#### Scenario: Lift is announced
- **WHEN** an area ban is lifted
- **THEN** one `area_ban.lifted` event is published and staff Discord receives it
