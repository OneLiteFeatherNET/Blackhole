# Quickstart: Validating the Appeal Workflow

Runnable validation for the flows in spec.md (US1 submission, US2 eligibility gating,
US3 review decisions, US4 SEVERE-tier full-lift ban, US5 supporting standing signal).
Every scenario needs a real punishment to appeal, created first via the `punishment`
feature's own endpoints (`specs/002-punishment-core/spec.md` owns those contracts — used
here only as setup, not re-specified).

## Prerequisites

```shell
cd docker && docker compose up -d && cd ..   # MariaDB + RabbitMQ — required, no mock path
```

The default `blackhole.appeal.min-days-manual` (14) and `min-days-auto` (3) make US2's
"before the wait elapsed" case trivial to hit (any fresh punishment) but the "after the
wait elapsed" case impractical to observe live without waiting two weeks. Override both
down to make eligibility observable within a session, the same way
`specs/001-elo-engine/quickstart.md` overrides the decay interval:

```shell
BLACKHOLE_APPEAL_MIN_DAYS_MANUAL=0 \
BLACKHOLE_APPEAL_MIN_DAYS_AUTO=0 \
BLACKHOLE_APPEAL_REPEAT_COOLDOWN_DAYS=1 \
./gradlew :backend:run
```

Revert the env overrides for anything beyond local scratch validation — `min-days-manual:0`
defeats the entire point of the wait-period gate (FR-004).

## Setup: create a punishment to appeal

1. Compute a SHA-512 hash to use as the test player (`OWNER`), and a fake reviewer/source
   UUID that is *not* the one about to issue the punishment:

   ```shell
   OWNER=$(printf 'quickstart-appeal-player' | sha512sum | cut -d' ' -f1)
   ISSUER="11111111-1111-1111-1111-111111111111"
   REVIEWER="22222222-2222-2222-2222-222222222222"
   ```

2. Create a **temporary, SERVER-scoped** template (STANDARD severity tier — used for the
   US1/US2/US3 scenarios):

   ```shell
   TEMPLATE_ID=$(curl -s -X POST localhost:8080/template/ \
     -H 'Content-Type: application/json' \
     -d '{"metaData":{"duration":"PT24H"},"reason":"quickstart test ban","type":"SERVER","eloDelta":0,"identifier":null}' \
     | jq -r '.identifier')
   ```

3. Apply it to `$OWNER`, issued by `$ISSUER` (a manual, non-ELO-system source):

   ```shell
   curl -s -X POST "localhost:8080/punishment/active/$OWNER/$TEMPLATE_ID/$ISSUER"
   PUNISHMENT_ID=$(curl -s "localhost:8080/punishment?owner=$OWNER" | jq -r '.content[0].identifier')
   ```

   (Adjust the lookup to however `GET /punishment` actually filters/paginates in your
   running build if it differs — the point is just to capture the punishment's
   `identifier` for the appeal payload below.)

## Scenario: US1/US2 — submission is recorded and immediately gated by the checklist

1. Submit an appeal against `$PUNISHMENT_ID` with a statement:

   ```shell
   curl -s -X POST localhost:8080/appeal/ \
     -H 'Content-Type: application/json' \
     -d "{\"punishmentIdentifier\":\"$PUNISHMENT_ID\",\"appellantHash\":\"$OWNER\",\"statement\":\"I was falsely flagged.\"}"
   ```

   **Expected** (with `min-days-manual:0` from Prerequisites): `status` is
   `ELIGIBLE_PENDING_REVIEW`, and `eligibilityCheckResult` is fully populated —
   `checklistVersion: 1`, `minDaysRequired: 0`, `minTimeElapsed: true`,
   `isRepeatAppeal: false`, `severityTier: "STANDARD"`, `isAutoTriggered: false` (issuer
   was `$ISSUER`, not the ELO system source), plus `supportingEloTrack`/
   `supportingEloScore`/`supportingEloRecovered` (spec US1 Acceptance Scenario 1 and 3,
   FR-003/FR-006). Save the returned `identifier` as `APPEAL_ID`.

   **Without** the env override (default `min-days-manual:14`), the same request instead
   returns `status: "INELIGIBLE"` with `minTimeElapsed: false` — this is the direct
   validation of spec US2 Acceptance Scenario 2.

2. Confirm a nonexistent punishment is rejected outright, no appeal record created:

   ```shell
   curl -si -X POST localhost:8080/appeal/ \
     -H 'Content-Type: application/json' \
     -d "{\"punishmentIdentifier\":\"does-not-exist\",\"appellantHash\":\"$OWNER\",\"statement\":\"test\"}"
   ```

   **Expected**: HTTP `404`, empty body (spec FR-002, SC-001).

3. Confirm the appeal appears in the operator list:

   ```shell
   curl -s "localhost:8080/appeal/?size=20" | jq '.content[] | select(.identifier=="'"$APPEAL_ID"'")'
   ```

   (spec FR-019.)

## Scenario: US2 — repeat-appeal cooldown blocks a new appeal after a denial

1. Deny the appeal from the previous scenario (see the review call in the next scenario
   for the exact request shape) so its status becomes `DENIED`.

2. Submit a second appeal against the *same* `$PUNISHMENT_ID`/`$OWNER` immediately after:

   ```shell
   curl -s -X POST localhost:8080/appeal/ \
     -H 'Content-Type: application/json' \
     -d "{\"punishmentIdentifier\":\"$PUNISHMENT_ID\",\"appellantHash\":\"$OWNER\",\"statement\":\"Please reconsider.\"}"
   ```

   **Expected**: `status: "INELIGIBLE"`, `eligibilityCheckResult.isRepeatAppeal: true`,
   even though `minTimeElapsed` is still `true` — the cooldown wins regardless of how much
   time has passed since the punishment itself (spec US2 Acceptance Scenario 3, FR-005).

## Scenario: US3 — a reviewer decides an eligible appeal

Using `APPEAL_ID` from a fresh eligible appeal (repeat steps under Setup + the US1
scenario with a new `$OWNER` if the previous one is now cooldown-blocked):

1. **Self-review rejected** — attempt to review using the punishment's own issuer as
   `reviewerId`:

   ```shell
   curl -si -X POST "localhost:8080/appeal/$APPEAL_ID/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"DENIED\",\"decisionNote\":\"self\",\"reviewerId\":\"$ISSUER\",\"newExpirationAt\":null}"
   ```

   **Expected**: HTTP `403` (spec FR-011, SC-005).

2. **Denial** — deny using a different reviewer:

   ```shell
   curl -s -X POST "localhost:8080/appeal/$APPEAL_ID/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"DENIED\",\"decisionNote\":\"Not convincing\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":null}"
   ```

   **Expected**: `status: "DENIED"`, `decidedBy: "$REVIEWER"`. Confirm the punishment is
   unchanged: `curl -s localhost:8080/punishment/active/$OWNER` (or your build's
   equivalent profile-read endpoint) still shows the same active ban with its original
   expiry (spec FR-015).

3. **Re-review rejected** — attempt to review the same, now-decided appeal again:

   ```shell
   curl -si -X POST "localhost:8080/appeal/$APPEAL_ID/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"GRANTED_FULL_LIFT\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":null}"
   ```

   **Expected**: HTTP `409` — "Appeal is not awaiting review" (spec FR-012).

4. **Full lift** — with a *fresh* eligible appeal (new `$OWNER`/punishment/appeal cycle),
   grant a full lift:

   ```shell
   curl -s -X POST "localhost:8080/appeal/$APPEAL_ID_2/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"GRANTED_FULL_LIFT\",\"decisionNote\":\"Reviewed evidence, lifting.\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":null}"
   ```

   **Expected**: `status: "GRANTED_FULL_LIFT"`. The punishment stops being enforced
   immediately — confirm via the profile/active-ban read endpoint that `$OWNER` no longer
   has this punishment in an active slot (spec FR-013, SC-006).

5. **Duration reduction** — with another fresh eligible appeal, grant a shorter expiry:

   ```shell
   NEW_EXPIRY=$(( $(date +%s%3N) + 3600000 ))  # 1 hour from now, epoch millis
   curl -s -X POST "localhost:8080/appeal/$APPEAL_ID_3/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"GRANTED_DURATION_REDUCTION\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":$NEW_EXPIRY}"
   ```

   **Expected**: `status: "GRANTED_DURATION_REDUCTION"`. The punishment remains active but
   with `expiration_date` now equal to `$NEW_EXPIRY` rather than its original, later
   expiry (spec FR-014).

6. **Invalid duration reduction** — omit `newExpirationAt`, or use a past timestamp:

   ```shell
   curl -si -X POST "localhost:8080/appeal/$APPEAL_ID_3/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"GRANTED_DURATION_REDUCTION\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":null}"
   ```

   **Expected**: HTTP `400` (spec FR-010, Edge Cases).

## Scenario: US4 — a SEVERE (permanent, network-wide) punishment can only be shortened

1. Create a **permanent NETWORK** template (no `duration` key in `metaData` at all):

   ```shell
   SEVERE_TEMPLATE_ID=$(curl -s -X POST localhost:8080/template/ \
     -H 'Content-Type: application/json' \
     -d '{"metaData":{},"reason":"quickstart permanent network ban","type":"NETWORK","eloDelta":0,"identifier":null}' \
     | jq -r '.identifier')
   ```

2. Apply it to a fresh `$OWNER2`, wait-gate it eligible the same way as Setup/US1, then
   attempt a full lift:

   ```shell
   curl -si -X POST "localhost:8080/appeal/$SEVERE_APPEAL_ID/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"GRANTED_FULL_LIFT\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":null}"
   ```

   **Expected**: HTTP `400` — "SEVERE punishments can only receive a duration reduction,
   never a full lift" (spec US4 Acceptance Scenario 1, FR-009, SC-004). Confirm via
   `GET /appeal/` that the appeal's `eligibilityCheckResult.severityTier == "SEVERE"`.

3. Attempt a duration reduction against the same appeal instead:

   ```shell
   NEW_EXPIRY=$(( $(date +%s%3N) + 3600000 ))
   curl -s -X POST "localhost:8080/appeal/$SEVERE_APPEAL_ID/review" \
     -H 'Content-Type: application/json' \
     -d "{\"decision\":\"GRANTED_DURATION_REDUCTION\",\"reviewerId\":\"$REVIEWER\",\"newExpirationAt\":$NEW_EXPIRY}"
   ```

   **Expected**: `200`, `status: "GRANTED_DURATION_REDUCTION"` — succeeds despite the
   SEVERE tier (spec US4 Acceptance Scenario 2, SC-004).

## Scenario: US5 — the supporting standing signal is informational only

1. Before submitting, give `$OWNER`'s *non-triggering* ELO track a below-baseline score
   via the ELO engine's own chat-scoring endpoint (see
   `specs/001-elo-engine/quickstart.md`) or leave it untouched entirely (no `EloProfileEntity`
   row at all).

2. Submit an appeal against a punishment for that player and inspect
   `eligibilityCheckResult.supportingEloTrack`/`supportingEloScore`/`supportingEloRecovered`.

   **Expected**: with no ELO profile at all, `supportingEloScore` equals
   `blackhole.elo.baseline` (default 1000) and `supportingEloRecovered: true` — a missing
   record defaults to baseline, never treated as missing/unknown (spec Edge Cases). In
   either case, `eligibilityCheckResult.eligible` is unaffected by this signal —
   compare two appeals with different supporting scores but identical `minTimeElapsed`/
   `isRepeatAppeal` and confirm `eligible` matches on both (spec US5 Acceptance Scenario 1,
   FR-018).

## References

- Request/response shapes: [contracts/appeal-api.md](./contracts/appeal-api.md)
- Entity fields and state transitions: [data-model.md](./data-model.md)
- Why each behavior above works this way: [research.md](./research.md)
- Punishment creation/revocation guarantees this feature relies on but doesn't redefine:
  `specs/002-punishment-core/spec.md`
