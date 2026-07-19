# Interface Contract: Appeal REST API

`AppealController`, mounted at `/appeal`, versioned via Micronaut's built-in scheme
(`@Version(ApiVersion.V1)` — resolved from `X-API-VERSION` header or `api-version`/`v`
query param, default version `1`; paths themselves carry no `/v1` prefix, per
`.claude/rules/controller-versioning.md`). No authentication — open per constitution
Principle II. `reviewerId`/`decidedBy` are unverified, client-supplied values (see
`plan.md` Constitution Check, Principle II).

## `POST /appeal/`

Submits an appeal against a punishment. Evaluated synchronously against the eligibility
checklist before the response is returned (spec US1, FR-001/FR-002/FR-003).

**Request body** (`AppealSubmissionDTO`):

```json
{
  "punishmentIdentifier": "<punishment id>",
  "appellantHash": "<128-hex-char SHA-512 hash>",
  "statement": "<up to 2000 chars>"
}
```

Validation: all three fields required/non-blank; `appellantHash` must match
`^[a-fA-F0-9]{128}$`; `statement` max 2000 chars. A request violating this is rejected
before reaching the controller body (standard Micronaut `@Valid` 400 response — not
modeled as a DTO `Error` variant; see `plan.md` Constitution Check Principle III note).

**Response 200** (`AppealDTO`):

```json
{
  "identifier": "b6b1...-uuid",
  "punishmentIdentifier": "<punishment id>",
  "appellantHash": "<hash>",
  "statement": "<text>",
  "status": "ELIGIBLE_PENDING_REVIEW",
  "eligibilityCheckResult": {
    "checklistVersion": 1,
    "minDaysRequired": 3,
    "daysSincePunishment": 4.2,
    "minTimeElapsed": true,
    "isRepeatAppeal": false,
    "repeatAppealCooldownDays": 7,
    "isAutoTriggered": true,
    "eloTriggerReasonCode": "TOXICITY_FLAG",
    "severityTier": "STANDARD",
    "supportingEloTrack": "GAMEPLAY",
    "supportingEloScore": 1000,
    "supportingEloRecovered": true,
    "eligible": true
  },
  "decidedBy": null,
  "decisionNote": null,
  "createdAt": 1768867200000,
  "updatedAt": 1768867200000,
  "metaData": {}
}
```

`status` is `ELIGIBLE_PENDING_REVIEW` when `eligibilityCheckResult.eligible == true`,
otherwise `INELIGIBLE`. Both outcomes return HTTP 200 with the same shape — ineligibility
is not an error, it's a recorded appeal state (spec US1 Acceptance Scenario 1).

**Response 404**: no body — returned when `punishmentIdentifier` does not correspond to
any existing punishment. No `AppealEntity` is created in this case (spec FR-002, SC-001).

**Side effect**: publishes an `appeal.submitted` domain event
(`{appealIdentifier, owner, punishmentIdentifier, eligible}`) on the `blackhole.events`
topic exchange via `DomainEventPublisher`, called directly from the controller (not
wrapped by a service — see `plan.md` Constitution Check Principle III).

## `GET /appeal/`

Retrieves a paginated list of all submitted appeals (spec FR-019). Accepts Micronaut's
standard `Pageable` query params (`page`, `size`, `sort`).

**Response 200** (`Page<AppealDTO>`):

```json
{
  "content": [ /* AppealDTO, shape as above */ ],
  "pageable": { "number": 0, "size": 20 },
  "totalSize": 1
}
```

No filtering by status/appellant/punishment is exposed by this contract — callers needing
e.g. "only appeals awaiting review" must filter client-side or page through the full list.

## `POST /appeal/{identifier}/review`

Decides an eligible appeal (spec US3/US4, FR-008 through FR-017). Path parameter
`identifier` is the appeal's UUID.

**Request body** (`AppealReviewDTO`):

```json
{
  "decision": "GRANTED_FULL_LIFT",
  "decisionNote": "<optional, up to 2000 chars>",
  "reviewerId": "<uuid>",
  "newExpirationAt": null
}
```

`decision` must be exactly one of `GRANTED_FULL_LIFT`, `GRANTED_DURATION_REDUCTION`,
`DENIED`. `newExpirationAt` (epoch millis) is required and must be strictly in the future
when `decision == GRANTED_DURATION_REDUCTION`; ignored otherwise.

**Response 200** (`AppealDTO`): the updated appeal, `status` now one of the three
terminal decision values, `decidedBy`/`decisionNote`/`updatedAt` populated.

**Response 404**: no body — `identifier` does not correspond to any existing appeal.

**Response 400**:
- `decision` is not one of the three valid values (FR-008).
- `decision == GRANTED_FULL_LIFT` against an appeal whose stored checklist
  `severityTier == "SEVERE"` (FR-007/FR-009) — message: `"SEVERE punishments can only
  receive a duration reduction, never a full lift"`.
- `decision == GRANTED_DURATION_REDUCTION` with a missing `newExpirationAt`, or one that
  is not strictly in the future (FR-010).

**Response 403**: `reviewerId` equals the punishment's own `source` — self-review
rejected before any change to enforcement state (FR-011, SC-005). Message: `"Reviewer
must not be the punishment's original source"`.

**Response 409**:
- The appeal's current `status` is not `ELIGIBLE_PENDING_REVIEW` or `IN_REVIEW` (in
  practice, always `ELIGIBLE_PENDING_REVIEW` today — see `data-model.md` on `IN_REVIEW`
  being currently unreachable) — message: `"Appeal is not awaiting review"` (FR-012).
- The underlying punishment is no longer the profile's active ban/chat-ban slot (already
  lifted or naturally expired) by the time the decision is applied — message:
  `"Punishment is no longer active"` (FR-016). The appeal is left in its prior state, not
  marked resolved.

**Side effects**:
- `GRANTED_FULL_LIFT`: the punishment stops being enforced immediately — moved out of the
  profile's active ban/chat-ban slot into history (FR-013). No `revokedBy`/update-date
  metadata is stamped onto the punishment itself in this path — see `research.md`
  "Decision mechanics are a fourth, independent implementation" for the resulting
  audit-trail gap.
- `GRANTED_DURATION_REDUCTION`: the punishment's `Expirable.META_DATA_KEY_EXPIRATION_DATE`
  metadata is overwritten with `newExpirationAt`, and its update-date metadata is
  refreshed (FR-014).
- `DENIED`: no change to the punishment at all (FR-015).
- Any of the three: `CacheInvalidationPublisher.invalidate(owner)` fires (except for
  `DENIED`, which returns before reaching the cache-invalidation call — see
  `AppealDecisionService.applyDecision`'s early `DENIED` return).
- Publishes an `appeal.resolved` domain event
  (`{appealIdentifier, owner, punishmentIdentifier, decision, type}`) on the
  `blackhole.events` topic exchange, regardless of which of the three decisions was made.

## Not part of this contract

- Eligibility re-evaluation on demand (e.g. "would this appeal be eligible right now") is
  not exposed as a separate endpoint — the checklist only ever runs once, at submission
  time, and its result is immutable thereafter (FR-006).
- There is no endpoint to fetch a single appeal by identifier, or to filter the list by
  status/punishment/appellant — only the full paginated list (`GET /appeal/`) and the
  create/review actions exist.
- Punishment revocation guarantees themselves (timing bound on enforcement stopping) are
  defined by `specs/002-punishment-core/spec.md`, not restated here — this contract only
  describes the appeal-specific trigger into that behavior.
