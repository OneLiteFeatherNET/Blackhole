# Quickstart: Validating Vanilla Ban-List Import

Runnable validation for the flows in spec.md (US1 migrate a list, US2 preview/dry-run,
US3 don't clobber existing punishments, US4 fault tolerance, US5 IP-ban acknowledgment).

## Prerequisites

```shell
cd docker && docker compose up -d && cd ..   # MariaDB + RabbitMQ — required, no mock path
./gradlew :backend:run
```

Backend listens on its configured port (examples below use `localhost:8080`). Swagger UI
at `/swagger/views/swagger-ui` will **not** list this endpoint — it's
`@Operation(hidden = true)` by design (spec FR-014); use the endpoint path directly.

## Sample vanilla ban-list files

Save these two files to try the scenarios below. Field names and the `yyyy-MM-dd
HH:mm:ss Z` timestamp format match vanilla Minecraft's own `banned-players.json`/
`banned-ips.json` exactly.

`banned-players.json`:

```json
[
  {
    "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "name": "Notch",
    "created": "2020-01-15 10:00:00 +0000",
    "source": "LegacyOpsPlugin",
    "expires": "forever",
    "reason": "Griefing spawn"
  },
  {
    "uuid": "853c80ef-3c37-49fd-aa49-938b674adae6",
    "name": "jeb_",
    "created": "2019-06-01 09:00:00 +0000",
    "source": "LegacyOpsPlugin",
    "expires": "2021-06-01 09:00:00 +0000",
    "reason": "Chat spam"
  },
  {
    "uuid": "not-a-real-uuid",
    "name": "GriefBot99",
    "created": "2022-03-10 12:00:00 +0000",
    "source": "LegacyOpsPlugin",
    "expires": "forever",
    "reason": "Xray hacking"
  }
]
```

Note entry 2's `expires` (2021) is already in the past relative to any 2026 test run —
this is intentional, exercising the already-expired-entry path. Entry 3's `uuid` is
deliberately malformed, exercising the fault-tolerance path.

`banned-ips.json`:

```json
[
  { "ip": "203.0.113.5", "created": "2020-01-15 10:00:00 +0000", "source": "LegacyOpsPlugin", "expires": "forever", "reason": "Griefing spawn" }
]
```

## Scenario: US2 — preview an import before committing (dry run)

```shell
curl -s -X POST "localhost:8080/admin/import/vanilla?dryRun=true" \
  -F "bannedPlayers=@banned-players.json;type=application/json" \
  -F "bannedIps=@banned-ips.json;type=application/json"
```

**Expected**: `dryRun: true`, `playersTotal: 3`, `playersImported: 2`,
`playersSkippedExisting: 0`, `playersInvalid: 1`, `invalidEntries` containing a message
naming `GriefBot99` and its bad `uuid`, `ipsTotal: 1`, `ipsSkipped: 1`. Confirm nothing
was actually written — query the `punishment`/`profile` features' own read endpoints for
either hashed owner (see FR-002 note below for computing the hash) and confirm no
profile exists yet.

## Scenario: US1 — real import migrates the list; already-expired entry lands in history, not active

Run the same call without `dryRun` (or `dryRun=false`):

```shell
curl -s -X POST "localhost:8080/admin/import/vanilla" \
  -F "bannedPlayers=@banned-players.json;type=application/json" \
  -F "bannedIps=@banned-ips.json;type=application/json"
```

**Expected**: same counts as the preview (`playersImported: 2`, `playersInvalid: 1`,
`ipsTotal: 1`) — confirming FR-009's "preview matches real outcome" guarantee (US2
Acceptance Scenario 2) — but this time punishments were actually created:

- Notch's profile (`forever` expiry) has an **active** network ban.
- jeb_'s profile (2021 expiry, already past) has **no active ban** — the punishment
  landed directly in `history` instead (spec FR-004, US1 Acceptance Scenario 2).
- Both punishments' `source` is the fixed import identity
  (`UUID.nameUUIDFromBytes("blackhole-vanilla-import".getBytes(UTF_8))` —
  compute it yourself to compare, or just confirm it's neither `null` nor a value you
  recognize as a real staff UUID or the ELO engine's own `SYSTEM_ELO_SOURCE`) — spec
  FR-005.

To compute the hashed `owner` for either UUID and inspect their profile via the
`punishment`/`profile` features' own endpoints (out of this feature's own contract, see
`specs/002-punishment-core/contracts/`):

```shell
# UUIDHasher.hash: SHA-512 of the UUID's toString() form, lowercase hex, no separators
printf '069a79f4-44e9-4726-a5be-fca90e38aaf5' | sha512sum | cut -d' ' -f1
```

## Scenario: US3 — re-running the same file is idempotent (already-punished players untouched)

Immediately re-run the exact same real-import call from the US1 scenario above:

```shell
curl -s -X POST "localhost:8080/admin/import/vanilla" \
  -F "bannedPlayers=@banned-players.json;type=application/json"
```

**Expected**: `playersSkippedExisting: 1` (Notch — active ban already exists, correctly
skipped), `playersInvalid: 1` (GriefBot99, same as before). **`playersImported` will be
`1`, not `0`** — jeb_'s already-expired entry is re-imported as a **second, duplicate**
history punishment. This is a real gap, not expected/desired behavior: the skip check
(`profile.getActiveBan() != null`) only guards the active-ban slot, so a profile whose
only prior punishment sits in `history` (the already-expired-entry path) is invisible to
it and gets re-processed every time the file is re-run. Confirm it yourself by inspecting
jeb_'s profile via the `punishment`/`profile` features' own read endpoints — `history`
will contain two near-identical punishment entries after this second run, growing by one
on every further re-run of the same file. This directly contradicts spec US3's
"Independent Test... confirm the second run creates no new or duplicate punishments for
players already imported by the first run" for the already-expired subset of entries —
see research.md "Known gap" and plan.md Complexity Tracking. No duplicate *active* ban
exists for Notch afterward (FR-006, SC-002 — that half of the idempotency guarantee does
hold).

## Scenario: US4 — malformed entries don't abort the whole import

Already exercised above via `GriefBot99` in every run — confirm independently that:

1. `invalidEntries` contains exactly one entry, with enough detail (`name` + raw `uuid`
   value) to find it in the source file (FR-007).
2. The other two (valid) entries still import successfully in the same response
   (`playersImported` reflects them) — a single bad record never zeroes out the rest.

To also exercise FR-008 (missing `reason`/`created` default rather than reject), add a
fourth entry with `"reason": ""` and no `created` field at all, re-run, and confirm it
appears in `playersImported`, not `invalidEntries` — its resulting punishment's template
reason will read `"Imported vanilla ban"` (`DEFAULT_REASON`) if you inspect it via the
`punishment` feature's own endpoints.

## Scenario: US5 — IP ban entries are acknowledged, not silently dropped

Already exercised above (`ipsTotal: 1`, `ipsSkipped: 1` whenever `bannedIps` is
supplied). To confirm the "no IP list provided" edge case (spec Acceptance Scenario 2),
omit the `-F "bannedIps=..."` part entirely:

```shell
curl -s -X POST "localhost:8080/admin/import/vanilla?dryRun=true" \
  -F "bannedPlayers=@banned-players.json;type=application/json"
```

**Expected**: `ipsTotal: 0`, `ipsSkipped: 0` — a valid zero-count summary, not an error
(spec Edge Cases, FR-010).

## Scenario: malformed multipart body is a transport-level 400, not a per-entry report

```shell
curl -si -X POST "localhost:8080/admin/import/vanilla" \
  -F "bannedPlayers=@/dev/null;type=application/json"
```

**Expected**: `400` with a plain-text `Failed to read uploaded file(s): ...` body (or,
depending on how the empty file deserializes, a `200` with `playersTotal: 0` — an empty
JSON array is valid input, not a transport failure; if you want to force the `400` path
specifically, upload a file containing invalid JSON syntax like `{not valid json`
instead). This distinguishes "the upload itself couldn't be read" (400, contracts/import-
api.md) from "the upload was read fine but individual entries were bad" (200 +
`invalidEntries`, FR-007).

## References

- Request/response shapes: [contracts/import-api.md](./contracts/import-api.md)
- Entry/result field definitions: [data-model.md](./data-model.md)
- Why each behavior above works this way, including the noted skip-check nuance in the
  US3 scenario: [research.md](./research.md)
