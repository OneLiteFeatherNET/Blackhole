# Quickstart: Validating Ban-Evasion Detection

Runnable validation for the flows in spec.md (US1 evasion signal, US2 unconditional
recording + unconfigured-salt behavior, US3 detection/retention windows). Because this
feature has no read/query endpoint (see `contracts/evasion-api.md` — it's write-only from
the REST API's perspective) and no wired-up event consumer, "did a signal fire" is
observed via the backend's own log output (`IpCorrelationService` logs a `WARN` line the
moment it publishes `evasion.detected`), not a follow-up REST call.

## Prerequisites

```shell
cd docker && docker compose up -d && cd ..   # MariaDB + RabbitMQ — required, no mock path
BLACKHOLE_EVASION_IP_SALT="quickstart-local-only-salt" ./gradlew :backend:run
```

`BLACKHOLE_EVASION_IP_SALT` **must** be set or every call below returns `503` (see
Scenario US2b). Never reuse a quickstart/dev salt value in a real deployment — see
`IpCorrelationService`'s Javadoc on the salt-rotation tradeoff before choosing a real one.
Backend listens on its configured port; Swagger UI at `/swagger/views/swagger-ui` if you
want to explore the contract interactively instead of curling it. Keep the backend's
stdout/log file visible in a second terminal — that's where the evasion signal surfaces.

## Scenario: US1 — two accounts sharing an IP raise a signal

1. Compute two SHA-512 hashes to use as test `owner` values (any 128-hex-char strings
   satisfying `EvasionRecordDTO`'s pattern work for a scratch run):

   ```shell
   OWNER_A=$(printf 'quickstart-player-a' | sha512sum | cut -d' ' -f1)
   OWNER_B=$(printf 'quickstart-player-b' | sha512sum | cut -d' ' -f1)
   SHARED_IP="203.0.113.42"
   ```

2. Record the first account's login from the shared IP:

   ```shell
   curl -si -X POST localhost:8080/evasion/record \
     -H 'Content-Type: application/json' \
     -d "{\"owner\":\"$OWNER_A\",\"ip\":\"$SHARED_IP\"}"
   ```

   **Expected**: HTTP `200`, empty body. No signal yet — this is the very first sighting
   ever recorded for that IP's token, and correlation requires at least two distinct
   accounts to compare (spec Edge Cases).

3. Record the second, distinct account's login from the **same** IP:

   ```shell
   curl -si -X POST localhost:8080/evasion/record \
     -H 'Content-Type: application/json' \
     -d "{\"owner\":\"$OWNER_B\",\"ip\":\"$SHARED_IP\"}"
   ```

   **Expected**: HTTP `200`, empty body — and, in the backend's log output around this
   call, a line like:

   ```text
   WARN ... IpCorrelationService ... Ban-evasion signal: 2 distinct owners share a token
   ```

   This confirms an `evasion.detected` event was published on the `blackhole.events`
   topic exchange naming both `$OWNER_A` and `$OWNER_B`'s hashes (never `$SHARED_IP`
   itself — spec FR-003, SC-003; verify by confirming `$SHARED_IP`'s literal value does
   not appear anywhere in the response bodies, request logs, or the WARN line above).

## Scenario: US2a — the same account reconnecting never raises a signal

```shell
for i in 1 2 3; do
  curl -si -X POST localhost:8080/evasion/record \
    -H 'Content-Type: application/json' \
    -d "{\"owner\":\"$OWNER_A\",\"ip\":\"198.51.100.7\"}" > /dev/null
done
```

**Expected**: three `200` responses, but **no** `"Ban-evasion signal"` WARN line appears
for `198.51.100.7` — one account reconnecting any number of times from the same IP never
raises a signal by itself (spec FR-005, SC-004, Edge Cases). Internally, the second and
third calls refresh the same row's `lastSeen`/`occurrenceCount` rather than creating new
rows (FR-007) — not independently observable via this API, but confirmable in the
database (`SELECT occurrence_count FROM ip_correlation_tokens WHERE owner_hash =
'$OWNER_A' AND token = <hmac of 198.51.100.7>` should read `3`, not three separate rows).

## Scenario: US2b — an unconfigured salt hard-fails rather than degrading

Stop the backend and restart it **without** `BLACKHOLE_EVASION_IP_SALT`:

```shell
./gradlew :backend:run
```

```shell
curl -si -X POST localhost:8080/evasion/record \
  -H 'Content-Type: application/json' \
  -d "{\"owner\":\"$OWNER_A\",\"ip\":\"198.51.100.7\"}"
```

**Expected**: HTTP `503`, body `Ban-evasion detection is not configured` (spec US2
Acceptance Scenario 3, FR-006). No new row is written for this call — confirm
`occurrence_count` for `$OWNER_A` is unchanged from Scenario US2a. Restart with
`BLACKHOLE_EVASION_IP_SALT` set again before continuing to the next scenario.

## Scenario: US3 — detection window bounds correlation; retention window deletes stale data

Both windows default to a scale (7 days detection, 90 days retention) impractical to
observe live in a short session — override both for a local run, similar to how the
ELO engine's decay quickstart shortens its own interval:

```shell
BLACKHOLE_EVASION_IP_SALT="quickstart-local-only-salt" \
BLACKHOLE_EVASION_DETECTION_WINDOW_DAYS=0 \
BLACKHOLE_EVASION_RETENTION_DAYS=0 \
BLACKHOLE_EVASION_RETENTION_SWEEP_CRON="*/10 * * * * *" \
./gradlew :backend:run
```

With `detection-window-days=0`, a sighting immediately falls outside the detection
window the moment its own timestamp is no longer `>=` the window start computed from
"now" at query time — repeat Scenario US1 with two fresh owners and confirm the
`"Ban-evasion signal"` WARN line **does not** appear this time (the two sightings never
overlap within a zero-width window), matching spec US3 Acceptance Scenario 1's
"separated by longer than the detection window" case at its most extreme boundary.

With `retention-days=0`, every existing row (including the ones from Scenarios US1/US2a
above, if the same database is reused) becomes immediately eligible for deletion. Wait
for the shortened cron (`*/10 * * * * *`, every 10 seconds) to fire at least once, then
confirm in the backend log:

```text
INFO ... IpCorrelationRetentionSweeper ... Deleted N IP correlation token(s) past the 0-day retention window
```

and that `SELECT count(*) FROM ip_correlation_tokens` reads `0` afterward (spec US3
Acceptance Scenario 2, FR-008, SC-005). Revert all four env overrides afterward — they
are for local validation only, not a recommended production config; `retention-
days=0` in particular would defeat the feature's own detection window in production
since nothing would survive long enough to correlate against.

## References

- Request/response shapes: [contracts/evasion-api.md](./contracts/evasion-api.md)
- Entity fields: [data-model.md](./data-model.md)
- Why each behavior above works this way: [research.md](./research.md)
