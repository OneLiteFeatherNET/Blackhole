# Interface Contract: Vanilla Ban-List Import API

`VanillaImportController`, mounted at `/admin/import`, versioned via Micronaut's built-in
scheme (`@Version(ApiVersion.V1)` — resolved from `X-API-VERSION` header or
`api-version`/`v` query param, default version `1`; paths themselves carry no `/v1`
prefix, per `.claude/rules/controller-versioning.md`). No authentication — open per
constitution Principle II. Hidden from the published OpenAPI/Swagger document
(`@Operation(hidden = true)`) — reachable, but not advertised (spec FR-014).

## `POST /admin/import/vanilla`

Bulk-imports a vanilla `banned-players.json` (required) and an optional
`banned-ips.json`, converting eligible player entries into Blackhole network-wide
punishments (spec US1–US5, FR-001–FR-013).

**Request**: `multipart/form-data`

| Part | Required | Notes |
|---|---|---|
| `bannedPlayers` | Yes | The vanilla `banned-players.json` file, as a JSON array of `VanillaBanEntry` (`uuid`, `name`, `created`, `source`, `expires`, `reason`). |
| `bannedIps` | No | The vanilla `banned-ips.json` file, as a JSON array of `VanillaIpBanEntry`. Omit entirely (not an empty part) to get `ipsTotal: 0` in the response. |

**Query parameters**:

| Param | Type | Default | Notes |
|---|---|---|---|
| `dryRun` | `boolean` | `false` | When `true`, reports the outcome without creating or changing any punishment/profile (spec FR-009, US2). |

Example request:

```shell
curl -s -X POST "localhost:8080/admin/import/vanilla?dryRun=true" \
  -F "bannedPlayers=@banned-players.json;type=application/json" \
  -F "bannedIps=@banned-ips.json;type=application/json"
```

**Response `200 OK`** (`VanillaImportResultDTO`):

```json
{
  "dryRun": true,
  "playersTotal": 3,
  "playersImported": 2,
  "playersSkippedExisting": 1,
  "playersInvalid": 0,
  "invalidEntries": [],
  "ipsTotal": 1,
  "ipsSkipped": 1
}
```

With one malformed entry present:

```json
{
  "dryRun": false,
  "playersTotal": 3,
  "playersImported": 1,
  "playersSkippedExisting": 1,
  "playersInvalid": 1,
  "invalidEntries": [
    "Invalid uuid for entry 'GriefBot99': not-a-real-uuid"
  ],
  "ipsTotal": 0,
  "ipsSkipped": 0
}
```

Same shape for both a real run and a `dryRun` preview (spec FR-013) — the only
observable difference is whether repository state actually changed (verifiable
separately via the `punishment`/`profile` features' own read endpoints, out of this
contract's scope) and whether `dryRun` is `true` in the body.

**Response `400 Bad Request`**: plain-text body (not a DTO — see plan.md Constitution
Check Principle III note), returned only when reading the uploaded file bytes itself
fails (`IOException`, e.g. a truncated multipart body) — **not** returned for malformed
*entries* inside an otherwise-readable file, which are reported per-entry in
`invalidEntries` on a `200` instead (FR-007's "reject only the individual entry"):

```text
Failed to read uploaded file(s): <exception message>
```

**Not returned by this endpoint**:
- `404` — there is no per-entry or per-import lookup surface; the endpoint always
  attempts to process whatever entries deserialize successfully.
- Any HTTP status distinguishing "some entries were invalid" from "all entries
  succeeded" — both cases return `200` with the counts reflecting the outcome (FR-007's
  fault-tolerance is expressed entirely in the response body, not the status code).

## Not part of this contract

- IP ban entries themselves are never returned, echoed, or individually described in the
  response — only their count (`ipsTotal`/`ipsSkipped`); no endpoint exists to inspect a
  specific IP ban entry's fields (spec FR-010: acknowledged, not carried over, not
  inspectable after the fact).
- The resulting punishments/profiles created by a real (non-`dryRun`) import are not
  returned inline — an operator confirms the outcome via the `punishment`/`profile`
  features' own read endpoints (`specs/002-punishment-core/contracts/`, out of scope
  here), or via the `punishment.created`/`profile.created` domain events on the
  `blackhole.events` exchange (spec FR-012) if a consumer is wired up.
- There is no endpoint to list or roll back a previous import run — re-running the same
  file is the only supported "undo an invalid import" workaround, and only works for
  entries not yet committed (already-imported entries are left untouched per FR-006, not
  reverted).
