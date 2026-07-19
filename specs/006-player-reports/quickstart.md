# Quickstart: Validating Player Reports

Runnable validation for the flows in spec.md (US1 submission + rate limiting, US2
resolution with/without punishment, US3 differing ELO effects, US4 operator review).

## Prerequisites

```shell
cd docker && docker compose up -d && cd ..   # MariaDB + RabbitMQ — required, no mock path
./gradlew :backend:run
```

Backend listens on its configured port; Swagger UI at `/swagger/views/swagger-ui` if you
want to explore the contract interactively instead of curling it. A real punishment
template must exist to exercise the punishment-application scenarios below — create one
via the `punishment` feature's own endpoints first (out of scope here; see
`specs/002-punishment-core/quickstart.md`) and capture its `identifier` as `$TEMPLATE_ID`.

## Scenario: US1 — submit a report and confirm it lands OPEN

```shell
REPORTER=$(printf 'quickstart-reporter' | sha512sum | cut -d' ' -f1)
REPORTED=$(printf 'quickstart-reported' | sha512sum | cut -d' ' -f1)

curl -s -X POST localhost:8080/report/ \
  -H 'Content-Type: application/json' \
  -d "{\"reporterHash\":\"$REPORTER\",\"reportedHash\":\"$REPORTED\",\"category\":\"CHEATING\",\"description\":\"saw them fly\"}"
```

**Expected**: HTTP 200, `{"status":"OPEN","resolvedBy":null,"resolutionNote":null,...}`
with a server-assigned `identifier`, `createdAt == updatedAt`. Capture `identifier` as
`$REPORT_ID` for the scenarios below.

## Scenario: US1 — per-reporter rate limit rejects excess submissions

Default limit is 5 reports per 10 minutes per `reporterHash`
(`blackhole.report.rate-limit.max-reports`). Repeat the submission above 5 more times
with the same `$REPORTER`:

```shell
for i in $(seq 1 6); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/report/ \
    -H 'Content-Type: application/json' \
    -d "{\"reporterHash\":\"$REPORTER\",\"reportedHash\":\"$REPORTED\",\"category\":\"OTHER\"}"
done
```

**Expected**: the first 5 (assuming this reporter has no other recent reports within the
window) return `200`; the 6th and beyond return `429` (spec FR-005, SC-001). Restart the
backend or use a fresh `$REPORTER` hash to reset for later scenarios, since the window
default is 10 minutes.

## Scenario: US2 — resolve a report without punishing (dismiss)

```shell
RESOLVER=$(uuidgen)

curl -s -X POST "localhost:8080/report/$REPORT_ID/resolve" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"DISMISSED\",\"resolutionNote\":\"not enough evidence\",\"resolvedBy\":\"$RESOLVER\"}"
```

**Expected**: HTTP 200, `status: "DISMISSED"`, `resolvedBy` set to `$RESOLVER`,
`updatedAt` advanced past `createdAt`. No punishment call was made (`punishmentTemplateId`
omitted) and, per spec FR-016, no ELO effect occurs — confirm via the ELO scenario below
that neither player's standing moved.

## Scenario: US2 + US3 — resolve as ACTIONED with a punishment template, confirm both ELO effects

Submit a fresh report first (rate limit permitting, or with a new `$REPORTER`/`$REPORTED`
pair), then resolve it with a punishment template attached:

```shell
NEW_REPORT_ID=$(curl -s -X POST localhost:8080/report/ \
  -H 'Content-Type: application/json' \
  -d "{\"reporterHash\":\"$REPORTER\",\"reportedHash\":\"$REPORTED\",\"category\":\"CHEATING\"}" \
  | jq -r .identifier)

curl -s -X POST "localhost:8080/report/$NEW_REPORT_ID/resolve" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"ACTIONED\",\"resolvedBy\":\"$RESOLVER\",\"punishmentTemplateId\":\"$TEMPLATE_ID\",\"punishmentSource\":\"$RESOLVER\"}"
```

**Expected**: HTTP 200, `status: "ACTIONED"`. Behind the scenes (spec US2 Acceptance
Scenario 2, US3 Acceptance Scenarios 2 and 4):
1. `PunishmentApplicationService.apply` created a punishment against `$REPORTED` —
   confirm via the punishment feature's own profile endpoint.
2. `$REPORTED`'s **gameplay** ELO (category `CHEATING` maps to `GAMEPLAY`) dropped by
   `blackhole.elo.report.actioned-delta` (default `-100`), reason `REPORT_ACTIONED`.
3. `$REPORTER`'s **gameplay** ELO rose by `blackhole.elo.report.reward-delta` (default
   `50`), reason `REPORT_REWARDED` — this only happens because `punishmentTemplateId` was
   supplied and succeeded.

Confirm both ELO effects via `specs/001-elo-engine`'s own read endpoints:

```shell
curl -s localhost:8080/elo/$REPORTED   # gameplayElo should read baseline (1000) - 100 = 900
curl -s localhost:8080/elo/$REPORTER   # gameplayElo should read baseline (1000) + 50 = 1050
curl -s "localhost:8080/elo/$REPORTED/history?sort=createdAt,desc"  # top entry: reasonCode REPORT_ACTIONED, delta -100
curl -s "localhost:8080/elo/$REPORTER/history?sort=createdAt,desc"  # top entry: reasonCode REPORT_REWARDED, delta 50
```

## Scenario: US3 — ACTIONED without a punishment template rewards nobody

Submit another fresh report, then resolve it ACTIONED **without** `punishmentTemplateId`:

```shell
NOTPUNISHED_ID=$(curl -s -X POST localhost:8080/report/ \
  -H 'Content-Type: application/json' \
  -d "{\"reporterHash\":\"$REPORTER\",\"reportedHash\":\"$REPORTED\",\"category\":\"CHAT_ABUSE\"}" \
  | jq -r .identifier)

curl -s -X POST "localhost:8080/report/$NOTPUNISHED_ID/resolve" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"ACTIONED\",\"resolvedBy\":\"$RESOLVER\"}"
```

**Expected**: HTTP 200. `$REPORTED`'s **chat** ELO (category `CHAT_ABUSE` maps to `CHAT`)
still drops by the actioned-delta (spec FR-014 fires unconditionally on any ACTIONED
resolution mapped to a track) — but `$REPORTER`'s chat ELO is **unaffected** (spec
FR-018, SC-004): confirm no new `REPORT_REWARDED` event appears in
`GET /elo/$REPORTER/history` after this call. This is the asymmetry documented in
`research.md` — the reported player's standing hit is unconditional on ACTIONED+track,
the reporter's reward is additionally conditional on a demonstrated punishment.

## Scenario: US3 — `OTHER` category never produces a standing change

```shell
OTHER_ID=$(curl -s -X POST localhost:8080/report/ \
  -H 'Content-Type: application/json' \
  -d "{\"reporterHash\":\"$REPORTER\",\"reportedHash\":\"$REPORTED\",\"category\":\"OTHER\"}" \
  | jq -r .identifier)

curl -s -X POST "localhost:8080/report/$OTHER_ID/resolve" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"ACTIONED\",\"resolvedBy\":\"$RESOLVER\"}"
```

**Expected**: HTTP 200, `status: "ACTIONED"` — but neither `$REPORTED`'s nor
`$REPORTER`'s ELO history gains a new entry from this call (spec FR-015).

## Scenario: US2 — a missing punishment template fails cleanly

```shell
GHOST_ID=$(curl -s -X POST localhost:8080/report/ \
  -H 'Content-Type: application/json' \
  -d "{\"reporterHash\":\"$REPORTER\",\"reportedHash\":\"$REPORTED\",\"category\":\"GRIEFING\"}" \
  | jq -r .identifier)

curl -si -X POST "localhost:8080/report/$GHOST_ID/resolve" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"ACTIONED\",\"resolvedBy\":\"$RESOLVER\",\"punishmentTemplateId\":\"$(uuidgen)\",\"punishmentSource\":\"$RESOLVER\"}"
```

**Expected**: HTTP `404`, empty body. Confirm the report is untouched afterward:

```shell
curl -s localhost:8080/report/ | jq ".content[] | select(.identifier==\"$GHOST_ID\")"
```

**Expected**: `status` still `"OPEN"`, `resolvedBy`/`resolutionNote` still `null` (spec
FR-013, SC-006 — no partial completion).

## Scenario: US2 — missing `punishmentSource` rejects before any mutation

```shell
curl -si -X POST "localhost:8080/report/$GHOST_ID/resolve" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"ACTIONED\",\"resolvedBy\":\"$RESOLVER\",\"punishmentTemplateId\":\"$TEMPLATE_ID\"}"
```

**Expected**: HTTP `400`, body `"punishmentSource is required when punishmentTemplateId is set"`
(spec FR-012).

## Scenario: US4 — review the full report queue

```shell
curl -s "localhost:8080/report/?page=0&size=20"
```

**Expected**: HTTP 200, a `Page<ReportDTO>` containing every report submitted above
across all statuses, each with its full current detail (spec FR-008, SC-005). An
unseeded/fresh deployment returns `{"content":[],...,"totalSize":0}` rather than an error
(spec US4 Acceptance Scenario 2).

## References

- Request/response shapes: [contracts/report-api.md](./contracts/report-api.md)
- Entity fields: [data-model.md](./data-model.md)
- Why each behavior above works this way: [research.md](./research.md)
- Downstream ELO threshold/decay behavior once a delta lands: `specs/001-elo-engine/quickstart.md`
