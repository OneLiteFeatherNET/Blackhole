# Spec Delta

## Purpose
Publishes a machine-readable security contact on the public hosts as an RFC 9116 `security.txt`, so that security researchers can report vulnerabilities. The content comes from operator-set runtime configuration, so that the `Expires` field is reviewed and renewed by people rather than drifting out of date.

## ADDED Requirements

### Requirement: Public disclosure file on both public hosts
The portal and the panel SHALL answer `GET /.well-known/security.txt` with an RFC 9116 document whose `Contact` and `Expires` fields come from configuration. The route MUST need no login, MUST NOT call the backend, and MUST NOT be reachable through the portal's `/api` allow-list.

#### Scenario: Anonymous request on the portal
- **WHEN** an anonymous client requests `https://appeal.onelitefeather.net/.well-known/security.txt`
- **THEN** the response is HTTP 200 with the configured fields and no backend request is made

#### Scenario: Anonymous request on the panel
- **WHEN** an anonymous client requests `https://blackhole.onelitefeather.net/.well-known/security.txt`
- **THEN** the response is HTTP 200 with the configured fields, not the single-page application

### Requirement: Content type and caching
The response SHALL have `Content-Type: text/plain; charset=utf-8` and `Cache-Control: public, max-age=86400`, and SHALL carry the same security headers as every other response of that app.

#### Scenario: Headers on a valid file
- **WHEN** the file is served
- **THEN** the Content-Type and Cache-Control headers match exactly and `X-Content-Type-Options: nosniff` is present

### Requirement: Unset contact disables the file
When `BLACKHOLE_SECURITY_CONTACT` is unset or empty, the app MUST start normally, MUST log exactly one warning naming the variable, and MUST answer the route with HTTP 404.

#### Scenario: Deployment without the setting
- **WHEN** the app starts with no `BLACKHOLE_SECURITY_CONTACT`
- **THEN** it listens, logs one warning and `GET /.well-known/security.txt` returns 404

### Requirement: Fields and their validation at startup
At startup the app SHALL validate every `BLACKHOLE_SECURITY_*` value and SHALL refuse to start with a non-zero exit code, naming each invalid variable, when a value is malformed. `Contact` values MUST be `mailto:`, `tel:` or `https:` URIs. `Expires` MUST be an RFC 3339 timestamp with a time zone, MUST be after the current time, and MUST NOT be more than one year after it. `Expires` MUST be set whenever `Contact` is set.

#### Scenario: Expires in the past
- **WHEN** the app starts with `BLACKHOLE_SECURITY_EXPIRES` set to a time before the current clock time
- **THEN** it exits with a non-zero code and the message names `BLACKHOLE_SECURITY_EXPIRES`

#### Scenario: Expires more than a year ahead
- **WHEN** `Expires` is set to a time more than one calendar year after the current clock time
- **THEN** it exits with a non-zero code naming the variable

#### Scenario: Contact without Expires
- **WHEN** `BLACKHOLE_SECURITY_CONTACT` is set and `BLACKHOLE_SECURITY_EXPIRES` is not
- **THEN** it exits with a non-zero code naming `BLACKHOLE_SECURITY_EXPIRES`

### Requirement: Canonical location per host
The `Canonical` field SHALL name the `https` URL of the file on the app's own public origin, `BLACKHOLE_PORTAL_ORIGIN` for the portal and `BLACKHOLE_UI_ORIGIN` for the panel, and SHALL NOT name the other host.

#### Scenario: Portal file names its own host
- **WHEN** the portal serves the file with `BLACKHOLE_PORTAL_ORIGIN=https://appeal.onelitefeather.net`
- **THEN** the `Canonical` field is `https://appeal.onelitefeather.net/.well-known/security.txt`

### Requirement: Optional fields
`Encryption`, `Acknowledgments` and `Policy` SHALL be emitted only when their variable is set, and `Preferred-Languages` SHALL default to `en, de`. Each value MUST be validated as its RFC 9116 type: `Encryption`, `Acknowledgments` and `Policy` are `https` URIs, and `Preferred-Languages` is a list of RFC 5646 language tags.

#### Scenario: Optional field unset
- **WHEN** `BLACKHOLE_SECURITY_POLICY` is not set
- **THEN** the served file has no `Policy` line

#### Scenario: Default languages
- **WHEN** no language variable is set
- **THEN** the served file has `Preferred-Languages: en, de`

### Requirement: Helm wiring with render-time checks
The chart SHALL expose a `securityTxt` values block with the fields above, SHALL render them as `BLACKHOLE_SECURITY_*` environment variables of the portal and web Deployments, and SHALL fail the render when `contact` is set without `expires`, or when `expires` is not an RFC 3339 timestamp with a time zone. The time window is not checked at render time: the apps check it at startup.

#### Scenario: Render with a valid block
- **WHEN** the chart is rendered with `securityTxt.contact` and a valid `securityTxt.expires`
- **THEN** both Deployments carry the matching `BLACKHOLE_SECURITY_CONTACT` and `BLACKHOLE_SECURITY_EXPIRES` environment variables

#### Scenario: Render with a malformed expiry
- **WHEN** the chart is rendered with `securityTxt.expires` set to `next year`
- **THEN** helm fails with a message naming `securityTxt.expires`

### Requirement: No personal contact in the repository
The repository and the chart defaults SHALL NOT contain a personal email address or a real security contact. `securityTxt.contact` defaults to empty.

#### Scenario: Chart defaults
- **WHEN** the default values are rendered
- **THEN** no `BLACKHOLE_SECURITY_CONTACT` variable is set and the route answers 404
