# Interface Contract: Ban-Evasion REST API

`EvasionController`, mounted at `/evasion`, versioned via Micronaut's built-in scheme
(`@Version(ApiVersion.V1)` — resolved from `X-API-VERSION` header or `api-version`/`v`
query param, default version `1`; the path itself carries no `/v1` prefix, per
`.claude/rules/controller-versioning.md`). No authentication — open per constitution
Principle II. This is the only endpoint in the `evasion/` package — there is no
read/query endpoint for the correlation table itself.

## `POST /evasion/record`

Records a login sighting and, as a side effect, checks for and publishes a ban-evasion
signal (spec US1, US2, FR-001, FR-004).

**Request body** (`EvasionRecordDTO`):

```json
{
  "owner": "<128-hex-char SHA-512 hash>",
  "ip": "<IP address string, as returned by Player#getRemoteAddress()>"
}
```

Validation: both fields required/non-blank; `owner` must match
`^[a-fA-F0-9]{128}$` (standard Micronaut `@Valid` 400 response on violation — not
modeled as a DTO `Error` variant; see plan.md's Constitution Check note on the DTO
contract). `ip` has no format validation beyond non-blank — it is consumed only as raw
bytes into an HMAC, never parsed, resolved, or used as a URL/host, so there is no SSRF
surface to validate against (constitution Principle V is N/A here, not merely
satisfied).

**Response 200**: empty body (`HttpResponse.ok()`). The sighting was recorded (or
refreshed) and the detection-window correlation check ran; no information about the
outcome of that check is returned synchronously — a caller cannot tell from this response
alone whether an `evasion.detected` event was published (see below).

**Response 503**: `blackhole.evasion.ip-salt` is not configured; evasion detection is
disabled deployment-wide. Body is a plain string, `"Ban-evasion detection is not
configured"` — no structured JSON error body. **No sighting is recorded and no
correlation check runs** in this case (FR-006) — this is a hard fail, not a
degraded-but-functioning mode. The Velocity caller (`PlayerLoginListener`) treats this
identically to any other `ApiException`: logged at `debug` level, login proceeds
regardless (spec Edge Cases).

**Not modeled**: a `400` from `@Valid` failing (e.g. malformed `owner`) returns
Micronaut's standard validation-error body, not a custom shape — same caveat as
`specs/001-elo-engine/contracts/elo-api.md`'s note on `POST /elo/chat`.

## Not part of this contract

**Observing a raised signal**: whether an evasion signal was raised for a given login is
never exposed synchronously in the `POST /evasion/record` response — it is only visible
as the `evasion.detected` domain event on the `blackhole.events` topic exchange (see
`data-model.md`'s Evasion Signal entity for its payload shape: `token` + `owners`). There
is no REST endpoint to query "has this account/IP been flagged" after the fact — a
caller would have to consume the event bus directly. As of this writing, no consumer of
`evasion.detected` exists anywhere in this repository (FR-010, spec Assumptions).

**Reading the correlation table**: there is no `GET` endpoint anywhere in the `evasion/`
package. Sightings are write-only from the REST API's perspective — the only other way
data leaves that table is deletion by `IpCorrelationRetentionSweeper`, never a read.

**Any enforcement action**: this contract never applies, queues, or references a
punishment, ELO delta, or any other consequence — see `specs/002-punishment-core/spec.md`
for where that decision, if ever made from this signal, would live.

## Spec pin

The endpoint is documented in the pinned OpenAPI spec consumed by the `client` module
(`client/build.gradle.kts` → `client/specs/blackhole-api-0.0.9.yml`) as `POST
/evasion/record`, matching this contract exactly (operationId `recordEvasionSighting`,
tags `["Evasion"]`, `200`/`503` responses only — no `400` documented in the generated
spec either). Earlier pinned spec versions (e.g. `0.0.5.yml`) show this endpoint at
`/evasion/{tenantId}/record`, a leftover from before the tenant-removal changesets
(constitution Principle I) — the current pin has no `tenantId` path segment.
