# Quickstart: Validating the Punishment Core

Runnable validation for the flows in spec.md (US1 apply-and-propagate, US2
at-most-one-active-per-track, US3 automatic expiry, US4 manual revoke, US5 template
management). Profile reads go through `GET /profile/{owner}` — owned by the sibling
`profile` feature package, used here only as the observation point for this feature's
own guarantees.

## Prerequisites

```shell
cd docker && docker compose up -d && cd ..   # MariaDB + RabbitMQ + Redis — required, no mock path
./gradlew :backend:run
```

Backend listens on its configured port; Swagger UI at `/swagger/views/swagger-ui` if
you want to explore the contract interactively instead of curling it.

```shell
OWNER=$(printf 'quickstart-punishment-player' | sha512sum | cut -d' ' -f1)
SOURCE=$(uuidgen)   # stands in for the unverified "who applied this" actor id
```

## Scenario: US1 — apply a punishment from a template, observable everywhere

1. Create a permanent NETWORK-ban template (no `duration` key = permanent, per FR-003):

   ```shell
   TEMPLATE_ID=$(curl -s -X POST localhost:8080/template/ \
     -H 'Content-Type: application/json' \
     -d '{"metaData":{},"reason":"Cheating","type":"NETWORK","eloDelta":0,"identifier":null}' \
     | jq -r '.identifier')
   echo "Template: $TEMPLATE_ID"
   ```

2. Apply it to `$OWNER`:

   ```shell
   curl -s -X POST "localhost:8080/punishment/active/$OWNER/$TEMPLATE_ID/$SOURCE" | jq .
   ```

   **Expected**: HTTP 200, `activeBan.type == "NETWORK"`, `activeBan.metaData` has no
   `expirationDate` key (permanent), `history == []` (nothing to rotate — first-ever
   punishment for this player, spec Edge Cases).

3. Confirm it's independently readable from the profile endpoint (this is what a proxy
   falls back to on a Redis cache miss, per FR-006/spec Assumptions):

   ```shell
   curl -s "localhost:8080/profile/$OWNER" | jq '.activeBan.type'
   ```

   **Expected**: `"NETWORK"`.

4. If you have `redis-cli` against the same Redis instance
   (`REDIS_URI`/`docker/docker-compose.yml`), confirm the mirror landed within a few
   seconds (spec SC-001):

   ```shell
   redis-cli GET "blackhole:punish:ban:$OWNER"
   ```

   **Expected**: a JSON blob with `"state":"SET"`, `"type":"NETWORK"`.

## Scenario: US2 — a second punishment on the same track supersedes the first

5. Create a second NETWORK template and apply it to the **same** `$OWNER`:

   ```shell
   TEMPLATE_ID_2=$(curl -s -X POST localhost:8080/template/ \
     -H 'Content-Type: application/json' \
     -d '{"metaData":{},"reason":"Repeat offense","type":"NETWORK","eloDelta":0,"identifier":null}' \
     | jq -r '.identifier')

   curl -s -X POST "localhost:8080/punishment/active/$OWNER/$TEMPLATE_ID_2/$SOURCE" | jq .
   ```

   **Expected**: HTTP 200, `activeBan.template.reason == "Repeat offense"` (the new
   one), `history` now contains exactly one entry whose `template.reason ==
   "Cheating"` (the first one, fully retrievable — spec US2 Acceptance Scenario 1 /
   SC-002).

6. Confirm the CHAT track is untouched (the two tracks are independent — spec US2
   Acceptance Scenario 2):

   ```shell
   curl -s "localhost:8080/profile/$OWNER" | jq '.activeChatBan'
   ```

   **Expected**: `null` — no chat punishment was ever applied to this player.

## Scenario: US3 — a temporary punishment lifts itself automatically

7. Create a short-duration CHAT template (`PT30S` = 30 seconds, `Duration.parse`
   format per FR-003) and apply it to a fresh owner so it doesn't interact with the
   NETWORK ban above:

   ```shell
   OWNER2=$(printf 'quickstart-punishment-player-2' | sha512sum | cut -d' ' -f1)
   CHAT_TEMPLATE_ID=$(curl -s -X POST localhost:8080/template/ \
     -H 'Content-Type: application/json' \
     -d '{"metaData":{"duration":"PT30S"},"reason":"Spam","type":"CHAT","eloDelta":0,"identifier":null}' \
     | jq -r '.identifier')

   curl -s -X POST "localhost:8080/punishment/active/$OWNER2/$CHAT_TEMPLATE_ID/$SOURCE" | jq '.activeChatBan.metaData'
   ```

   **Expected**: `expirationDate` present, roughly `now + 30000`.

8. Wait past the expiry, then read the profile directly:

   ```shell
   sleep 31
   curl -s "localhost:8080/profile/$OWNER2" | jq '{activeChatBan, historyCount: (.history | length)}'
   ```

   **Expected**: `activeChatBan == null`, `historyCount == 1` — the lazy-expiry check
   in `ProfileController.getById` moved it into history on this very read, even though
   `PunishmentExpirySweeper` (1-minute cadence) likely hasn't run yet (spec US3
   Acceptance Scenario 1).

9. To also observe the sweep-driven path (rather than the lazy-read path), apply
   another short-duration punishment to a third owner, wait past both the expiry *and*
   a full sweep cycle, and check without an intervening `GET /profile/{owner}` call in
   between — confirm the Redis key from Scenario US1 step 4 is cleared
   (`redis-cli GET "blackhole:punish:chatban:$OWNER3"` returns nil) even though nobody
   read the profile via the API, satisfying spec US3 Acceptance Scenario 2's
   "network-wide propagated state is updated" without a direct read triggering it.

## Scenario: US4 — staff revoke an active punishment early

10. Revoke `$OWNER`'s active NETWORK ban from Scenario US2:

    ```shell
    curl -s -X POST "localhost:8080/punishment/active/$OWNER/ban/revoke/$SOURCE" | jq '.activeBan, (.history | length)'
    ```

    **Expected**: `activeBan == null`, `history` length now 2 (the original superseded
    punishment plus this newly-revoked one). Fetch the history entry and confirm
    `metaData.revokedBy == "$SOURCE"` — distinguishing it from an expiry, which never
    sets this key (spec US4 Acceptance Scenario 1).

11. Attempt to revoke again (nothing active left on that track):

    ```shell
    curl -si -X POST "localhost:8080/punishment/active/$OWNER/ban/revoke/$SOURCE"
    ```

    **Expected**: HTTP `404`, no body — reported clearly rather than silently
    succeeding (FR-013).

## Scenario: US5 — templates can be created, updated, and removed independently

12. Update the reason on `$TEMPLATE_ID_2` and confirm the already-applied punishment
    from step 5 is unaffected:

    ```shell
    curl -s -X POST localhost:8080/template/update \
      -H 'Content-Type: application/json' \
      -d "{\"metaData\":{},\"reason\":\"Updated reason\",\"type\":\"NETWORK\",\"eloDelta\":0,\"identifier\":\"$TEMPLATE_ID_2\"}" \
      | jq '.reason'

    curl -s "localhost:8080/profile/$OWNER" | jq '.history[] | select(.template.identifier == "'"$TEMPLATE_ID_2"'") | .template.reason'
    ```

    **Expected**: the template fetch shows `"Updated reason"`; the historical
    punishment's embedded `template.reason` — since `PunishmentEntity.template` is a
    live `@ManyToOne` reference, not a snapshot — **also** now shows `"Updated
    reason"`. This is worth confirming explicitly: it means the "future applications
    use the new values; already-applied punishments ... unaffected" guarantee (spec
    US5 Acceptance Scenario 2) holds only for the *type* field (which is snapshotted
    onto `PunishmentEntity.type`) and not for `reason`/`metaData` display, which is
    live. Flag for `/speckit-tasks` if display-time immutability of `reason` was the
    intended guarantee.

13. Delete a template with no punishments ever applied from it:

    ```shell
    UNUSED_TEMPLATE_ID=$(curl -s -X POST localhost:8080/template/ \
      -H 'Content-Type: application/json' \
      -d '{"metaData":{},"reason":"Test only","type":"SERVER","eloDelta":0,"identifier":null}' \
      | jq -r '.identifier')

    curl -si -X DELETE "localhost:8080/template/delete/$UNUSED_TEMPLATE_ID"
    ```

    **Expected**: HTTP 200. Then `GET /template/$UNUSED_TEMPLATE_ID` returns 404 — no
    longer available to apply (spec US5 Acceptance Scenario 3).

14. Confirm the catalog reflects it immediately, with zero restart (spec SC-006):

    ```shell
    curl -s "localhost:8080/template/" | jq '.content | length'
    ```

    **Expected**: reflects the current template count without any redeploy between
    steps 1–14.

## References

- Request/response shapes: [contracts/punishment-api.md](./contracts/punishment-api.md)
- Entity fields: [data-model.md](./data-model.md)
- Why each behavior above works this way: [research.md](./research.md)
