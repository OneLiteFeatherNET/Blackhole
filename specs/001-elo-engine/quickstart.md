# Quickstart: Validating the Dual-ELO Punishment Engine

Runnable validation for the flows in spec.md (US1 automatic punishment, US2 chat
toxicity, US4 operator review). US3 (decay) is called out separately since it needs
either elapsed real time or a lowered decay interval to observe within a session.

## Prerequisites

```shell
cd docker && docker compose up -d && cd ..   # MariaDB + RabbitMQ — required, no mock path
./gradlew :backend:run
```

Backend listens on its configured port; Swagger UI at `/swagger/views/swagger-ui` if you
want to explore the contract interactively instead of curling it.

## Scenario: US2 — a toxic message drops chat standing and creates evidence

1. Compute a SHA-512 hash to use as a test `owner` (any 128-hex-char string satisfying
   `ChatSignalDTO`'s pattern works for a scratch run):

   ```shell
   OWNER=$(printf 'quickstart-test-player' | sha512sum | cut -d' ' -f1)
   ```

2. Submit a message containing a default-configured toxic keyword (`stupid`, `idiot`,
   `shut up`, `hate you` — see `blackhole.elo.chat.toxic-patterns`):

   ```shell
   curl -s -X POST localhost:8080/elo/chat \
     -H 'Content-Type: application/json' \
     -d "{\"owner\":\"$OWNER\",\"message\":\"you are so stupid\"}"
   ```

   **Expected**: `{"flagged":true,"score":...}` with `score >= 0.3` (the default
   `flag-threshold`).

3. Confirm the profile reflects a lower-than-baseline chat score:

   ```shell
   curl -s localhost:8080/elo/$OWNER
   ```

   **Expected**: `chatElo < 1000` (baseline), `gameplayElo == 1000` (untouched — tracks
   are independent, spec US1 Acceptance Scenario 2 / Assumptions).

4. Confirm the audit trail recorded it:

   ```shell
   curl -s "localhost:8080/elo/$OWNER/history?sort=createdAt,desc"
   ```

   **Expected**: one entry with `reasonCode: "TOXICITY_FLAG"`, a `sourceEvidenceId` set,
   and `metaData.score` matching step 2's score. Confirm the raw message text
   `"you are so stupid"` does not appear anywhere in this response (spec FR-003 / SC-003).

## Scenario: US1 — crossing the soft threshold auto-punishes

Repeat the `POST /elo/chat` call from step 2 above (same `$OWNER`) enough times that
`chatElo` crosses the soft threshold (default 700 — each default-severity flag costs
~30 points at `flag-delta=-50 * score`, so roughly 6-10 repeats depending on scored
severity; check `GET /elo/$OWNER` between attempts rather than guessing a fixed count).

**Expected**: once `chatElo` drops from ≥700 to <700 in one call, that same
`GET /elo/$OWNER/history` response gains an event with `reasonCode: "TOXICITY_FLAG"`
whose `resultingScore < 700`, and — per spec FR-005/SC-001 — a temporary chat
punishment now exists for `$OWNER` in the `punishment` feature's own data (out of this
contract's scope to query directly here; cross-check via that feature's own endpoint or
`elo.threshold_crossed` event if RabbitMQ consumption is wired up locally). Further
repeats **must not** add a second automatic punishment while still below threshold
(spec FR-007/SC-006) — confirm no second `THRESHOLD_BAN`-flavored consequence appears.

## Scenario: US4 — operator review of a player with no standing yet

```shell
curl -si localhost:8080/elo/$(printf 'never-seen-player' | sha512sum | cut -d' ' -f1)
```

**Expected**: HTTP `404` with an empty body (spec US4 Acceptance Scenario 2) — not a
fabricated baseline profile.

## Scenario: US3 — decay recovery (needs a shortened interval to observe live)

Decay defaults to a 24h interval (`blackhole.elo.decay.interval-ms`) and a 03:00 nightly
sweep cron — not practical to observe in a short session at default config. To validate
locally, override both for the run:

```shell
BLACKHOLE_ELO_DECAY_INTERVAL_MS=60000 \
BLACKHOLE_ELO_DECAY_SWEEP_CRON="*/1 * * * * *" \
./gradlew :backend:run
```

Then repeat the "confirm chat score" step from Scenario US2 a minute apart with no
further violations in between, and confirm `chatElo` moves back toward 1000 (never past
it) and a corresponding `DECAY_RECOVERY` event appears in the history (spec FR-010,
SC-004). Revert the env overrides afterward — they are for local validation only, not a
recommended production config.

## References

- Request/response shapes: [contracts/elo-api.md](./contracts/elo-api.md)
- Entity fields: [data-model.md](./data-model.md)
- Why each behavior above works this way: [research.md](./research.md)
