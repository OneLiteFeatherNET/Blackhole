# Interface Contract: ELO REST API

`EloController`, mounted at `/elo`, versioned via Micronaut's built-in scheme
(`@Version(ApiVersion.V1)` — resolved from `X-API-VERSION` header or `api-version`/`v`
query param, default version `1`; paths themselves carry no `/v1` prefix, per
`.claude/rules/controller-versioning.md`). No authentication — open per constitution
Principle II.

## `POST /elo/chat`

Scores a chat message for toxicity; if flagged, records evidence and applies a chat-ELO
delta (spec US2, FR-002/FR-003).

**Request body** (`ChatSignalDTO`):

```json
{
  "owner": "<128-hex-char SHA-512 hash>",
  "message": "<chat text, max 512 chars>"
}
```

Validation: both fields required/non-blank; `owner` must match
`^[a-fA-F0-9]{128}$`; a request violating this is rejected before reaching the service
(standard Micronaut `@Valid` 400 response — not modeled as a DTO `Error` variant, see
plan.md Constitution Check note on the DTO contract).

**Response 200** (`ChatToxicityResult`):

```json
{ "flagged": true, "score": 0.62 }
```

`flagged=false` means the message scored below `blackhole.elo.chat.flag-threshold`; no
evidence was recorded and no ELO delta was applied. `flagged=true` means a chat-ELO
delta was applied to `owner` as a side effect (not returned in this response — clients
needing the new score must call `GET /elo/{owner}` separately).

## `GET /elo/{owner}`

Dashboard read: a player's current standing (spec US4, FR-012).

**Response 200** (`EloProfileDTO`):

```json
{
  "owner": "<hash>",
  "chatElo": 940,
  "gameplayElo": 1000,
  "chatEloUpdatedAt": 1768867200000,
  "gameplayEloUpdatedAt": 1768780800000,
  "metaData": {}
}
```

**Response 404**: no body — returned when no profile exists yet for `owner` (spec US4
Acceptance Scenario 2: "clearly indicates none exists rather than returning a
fabricated baseline value").

## `GET /elo/{owner}/history`

Dashboard read: paginated audit trail (spec US4, FR-012). Accepts Micronaut's standard
`Pageable` query params (`page`, `size`, `sort`).

**Response 200** (`Page<EloEventDTO>`):

```json
{
  "content": [
    {
      "identifier": "b6b1...-uuid",
      "owner": "<hash>",
      "track": "CHAT",
      "delta": -60,
      "reasonCode": "TOXICITY_FLAG",
      "sourceEvidenceId": "9e21...-uuid",
      "resultingScore": 940,
      "createdAt": 1768867200000,
      "metaData": { "score": 0.62 }
    }
  ],
  "pageable": { "number": 0, "size": 20 },
  "totalSize": 1
}
```

Ordering is whatever `EloEventRepository.findByOwner(owner, pageable)`'s default/
`Pageable`-supplied sort resolves to — no explicit `ORDER BY createdAt` is guaranteed by
this contract unless the caller supplies `sort=createdAt,desc`. Flag for `/speckit-tasks`
if spec US4 Acceptance Scenario 3 ("returned in order") needs a server-side default sort
to hold without the caller specifying one.

## Not part of this contract

Threshold-crossing consequences (auto-applying a punishment template) are **not**
exposed as a direct API call — they are an internal side effect of `POST /elo/chat` (and
of other packages' calls into `EloService.applyDelta`, e.g. anticheat/report/manual-
adjustment paths owned by other feature packages, out of scope for this contract).
Observability into a threshold crossing itself is via the `elo.threshold_crossed`
domain event on the `blackhole.events` exchange, not a REST response field.
