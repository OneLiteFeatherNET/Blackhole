# Interface Contract: Player Reports REST API

`ReportController`, mounted at `/report`, versioned via Micronaut's built-in scheme
(`@Version(ApiVersion.V1)` — resolved from `X-API-VERSION` header or `api-version`/`v`
query param, default version `1`; paths themselves carry no `/v1` prefix, per
`.claude/rules/controller-versioning.md`). No authentication — open per constitution
Principle II. All three endpoints are handled directly by the controller; there is no
service layer to describe separately (see `plan.md` Constitution Check, Principle III).

## `POST /report/`

Submits a player report. Rate-limited per `reporterHash` and, independently, network-wide
(spec US1, FR-001–FR-006).

**Request body** (`ReportRequestDTO`):

```json
{
  "reporterHash": "<128-hex-char SHA-512 hash>",
  "reportedHash": "<128-hex-char SHA-512 hash>",
  "category": "CHAT_ABUSE",
  "description": "optional, max 1000 chars",
  "evidenceReferences": ["<uuid>", "..."],
  "metaData": {}
}
```

Validation: `reporterHash`/`reportedHash` required, non-blank, must match
`^[a-fA-F0-9]{128}$`; `category` required (one of `CHAT_ABUSE`, `CHEATING`, `GRIEFING`,
`OTHER`); `description` optional, max 1000 chars; `evidenceReferences`/`metaData`
optional, default to an empty list/map server-side if omitted. A request violating field
validation is rejected before reaching controller logic (standard Micronaut `@Valid` 400
response — not modeled as a DTO `Error` variant, consistent with the DTO-contract gap
noted in `plan.md`).

**Response 200** (`ReportDTO`): the persisted report, `status: "OPEN"`,
`resolvedBy`/`resolutionNote` both `null`, `identifier`/`createdAt`/`updatedAt`
server-assigned.

**Response 429**: no body. Returned when either the network-wide submission count or the
per-`reporterHash` submission count within the rolling `blackhole.report.rate-limit.window`
(default `PT10M`) has already reached its configured maximum
(`blackhole.report.rate-limit.max-reports-network-wide` default `50`,
`blackhole.report.rate-limit.max-reports` default `5`) — the network-wide check runs
first, but either alone is sufficient to reject (spec FR-005, FR-006, SC-001, SC-002).

**Side effect**: publishes a `report.created` domain event on submission (`reportIdentifier`,
`reporterHash`, `reportedHash`, `category`) — spec FR-007.

## `GET /report/`

Retrieves the full report queue, paginated, across all statuses (spec US4, FR-008).

**Query params**: standard Micronaut `Pageable` (`page`, `size`, `sort`).

**Response 200** (`Page<ReportDTO>`):

```json
{
  "content": [
    {
      "identifier": "b6b1...-uuid",
      "reporterHash": "<hash>",
      "reportedHash": "<hash>",
      "category": "CHEATING",
      "description": "saw them fly",
      "evidenceReferences": [],
      "status": "OPEN",
      "createdAt": 1768867200000,
      "updatedAt": 1768867200000,
      "resolvedBy": null,
      "resolutionNote": null,
      "metaData": {}
    }
  ],
  "pageable": { "number": 0, "size": 20 },
  "totalSize": 1
}
```

An empty report queue returns an empty `content` array with `totalSize: 0`, not an error
(spec US4 Acceptance Scenario 2).

## `POST /report/{identifier}/resolve`

Resolves an existing report, optionally applying a punishment template to the reported
player in the same call (spec US2, US3, FR-009–FR-019).

**Path param**: `identifier` — the report's UUID.

**Request body** (`ReportResolutionDTO`):

```json
{
  "status": "ACTIONED",
  "resolutionNote": "optional, max 1000 chars",
  "resolvedBy": "<uuid>",
  "punishmentTemplateId": "<uuid, optional>",
  "punishmentSource": "<uuid, required if punishmentTemplateId is set>"
}
```

Validation: `status` required (one of `OPEN`, `UNDER_REVIEW`, `ACTIONED`, `DISMISSED` —
no restriction on which prior status it may follow); `resolvedBy` required;
`resolutionNote` optional, max 1000 chars; `punishmentTemplateId`/`punishmentSource`
both optional, but `punishmentSource` becomes effectively required the moment
`punishmentTemplateId` is set (enforced in controller logic, not a DTO-level
cross-field validation annotation).

**Response 200** (`ReportDTO`): the updated report reflecting the new `status`,
`resolutionNote`, `resolvedBy`, and `updatedAt`.

**Response 400**: `"punishmentSource is required when punishmentTemplateId is set"` —
returned before any mutation (report and punishment both untouched) when
`punishmentTemplateId` is present but `punishmentSource` is `null` (spec FR-012).

**Response 404**:
- The path `identifier` does not match any existing report (spec FR-010) — no body,
  nothing else in the request is evaluated.
- `punishmentTemplateId` is present but `PunishmentApplicationService.apply(...)` returns
  empty (the template no longer exists) — no body; the report is left completely
  unmutated, since the punishment call happens before any field on the report entity is
  touched (spec FR-013, SC-006; see `research.md`).

**Side effects** (only when the request did not short-circuit into one of the failure
responses above):
1. If `punishmentTemplateId` is set: `PunishmentApplicationService.apply(reportedHash,
   punishmentTemplateId, punishmentSource)` runs first — this is the report system's only
   integration point with the punishment system (spec FR-011).
2. The report is updated (`status`/`resolutionNote`/`resolvedBy`/`updatedAt`).
3. If `status == ACTIONED` and the category maps to a track (`CHAT_ABUSE→CHAT`,
   `CHEATING`/`GRIEFING→GAMEPLAY`, `OTHER→` no track): `EloService.applyDelta` docks the
   reported player's standing on that track by `blackhole.elo.report.actioned-delta`
   (default `-100`), reason `REPORT_ACTIONED` (spec FR-014, FR-015).
4. If step 3 ran **and** `punishmentTemplateId` was set (i.e. a punishment was
   demonstrably applied in step 1): `EloService.applyDelta` additionally rewards the
   reporter's standing on the same track by `blackhole.elo.report.reward-delta` (default
   `50`), reason `REPORT_REWARDED` (spec FR-017, FR-018).
5. A `report.resolved` domain event is published (`reportIdentifier`, `reportedHash`,
   `status`, `resolvedBy`) — spec FR-019.

None of steps 1–5 are wrapped in a transaction (`@Transactional` is unusable project-wide
— constitution Principle VI); a failure partway through this chain is not rolled back.
See `plan.md` Constitution Check (Principle VI, PARTIAL) and `research.md`.

## Not part of this contract

- Evidence-reference validation: `evidenceReferences` UUIDs are stored opaquely and never
  checked against the `punishment` package's evidence store by this feature.
- Punishment template lookup/application semantics: owned by
  `specs/002-punishment-core/`'s `PunishmentApplicationService`.
- ELO threshold-crossing/auto-punishment consequences of either `applyDelta` call in step
  3/4 above: owned by `specs/001-elo-engine/`'s `EloService.checkThresholds` — observable
  only via that feature's own `elo.threshold_crossed` event, not a field on this
  contract's responses.
- Per-actor identity verification for `reporterHash`, `resolvedBy`, or `punishmentSource`:
  none exists (constitution Principle II) — all three are accepted as supplied.
