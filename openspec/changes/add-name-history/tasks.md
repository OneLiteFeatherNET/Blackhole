## 1. Name history store (backend)

- [ ] 1.1 Write the failing JUnit test for the name store: a first login with a name creates a row with the raw UUID and first and last seen equal; a second login with the same name in another case updates `last_seen` and keeps one row; both use a fresh fixture and an injected `java.time.Clock`.
- [ ] 1.2 Add the Flyway migration `V<next>__player_name_history.sql` (next free version after the sightings migration of `add-player-intelligence`): the table with `player_uuid` and primary key `(owner_hash, name_lower)`, the index on `name_lower`, and the one-time backfill `INSERT ... SELECT` from `player_sighting`.
- [ ] 1.3 Write the failing migration test that runs the backfill on a small sightings fixture and asserts one row per owner and lower-case name, carrying the UUID, with the earliest first seen and latest last seen.
- [ ] 1.4 Implement `NameHistoryRepository` with the single-statement upsert and `NameHistoryService`; make 1.1 pass. Call it from `SessionService.start` after the session is stored.
- [ ] 1.5 Write the failing test for the name retention sweeper: a row last seen 200 days ago is deleted and one last seen 10 days ago is kept.
- [ ] 1.6 Implement `NameHistorySweeper` on `blackhole.intel.name-retention-sweep-cron`; add `blackhole.intel.name-retention-days` and the sweep cron to `application.yml` with `${ENV_VAR:default}` and a comment; make 1.5 pass.
- [ ] 1.7 Write the failing test that erasure of an owner removes its name rows and leaves other owners' rows; implement it in the existing erasure path.

## 2. Routes (backend and client spec)

- [ ] 2.1 Write the failing controller-level test for `GET /intel/players/{owner}/names` (VIEWER and above, PROXY, newest first, paginated, the raw UUID in the body, malformed owner is a validation error) and for `GET /intel/names/{name}/owners` (case-insensitive exact match, at most 20 owners, empty list when unknown, no match for a partial name).
- [ ] 2.2 Implement `NameHistoryApi` (OpenAPI annotations), a thin controller and the sealed `NameHistoryDTO` with `Response`, `OwnerResponse` and `Error`; make 2.1 pass.
- [ ] 2.3 Regenerate the client: update `client/specs/blackhole-api-*.yml` to the next free version and `client/build.gradle.kts` `inputSpec`; run `./gradlew :client:openApiGenerate`.

## 3. Velocity command

- [ ] 3.1 Write the failing unit test for `/blackhole <user> names` resolution order: an online player is resolved from the hash without a backend call; an offline name is resolved through the live lookup; a name the live lookup does not know is resolved through the old-name route. Use a fake backend client, no network.
- [ ] 3.2 Write the failing unit test for the output: the UUID, then names newest first, at most 20, the permission `blackhole.user.info` is required, and a backend failure prints the failure message without throwing.
- [ ] 3.3 Implement the `names` subcommand in the Velocity command for player info, and add the messages to `blackhole_en.properties` and `blackhole_de.properties`; make 3.1 and 3.2 pass.

## 4. Panel (web)

- [ ] 4.1 Write the failing unit test for the search fallback: a live-lookup miss calls the old-name route, and a single owner result navigates to that owner's player page; several owners show a choice list; no owner shows the not-found message.
- [ ] 4.2 Write the failing component test for the "Name history" section: names newest first with first and last seen, the history start date note, the empty state, and the error state.
- [ ] 4.3 Implement the search fallback in `PlayersView` and the section in `PlayerDetailView`; add the strings to the locale files; make 4.1 and 4.2 pass.
- [ ] 4.4 Write the failing test that the search fallback and the section write nothing to browser storage; make it pass.

## 5. Code pull request

- [ ] 5.1 Open the code pull request titled `feat(profile): add name history from player sessions and old-name lookup` from the implementation branch, based on `main` after `add-player-intelligence` and `amend-data-protection-principle` are merged. Its commits follow the type rules and it touches nothing under `openspec/`.
