# Design

## Context

Principles that bind this design (`openspec/config.yaml`):

- **P1 GDPR by design.** Player identity may be stored clear where a feature needs it, with a retention period and
  erasure. Published data (public pages, events to third parties) carries no IP, raw UUID or player name. Detections
  are staff data and are never published.
- **P2 Entra, machine principals.** Every route except `GET /health` needs an Entra token and `@Secured`. Machine
  callers use app roles. The actor of a write is the token's identity, never a body field.
- **P3 Layering.** Thin controller, one service call, sealed DTOs, `*Api` interface for OpenAPI.
- **P4 Dual ELO stays automatic and contestable.** This change adds no input to the ELO engine.
- **P5 Infra owns rate limiting; SSRF for caller URLs.** This design has no caller-supplied URL.
- **P6 Append-only Flyway, `@Transactional` unusable.** The migration is new; the service does not rely on `@Transactional`
  (see `.claude/rules/database.md`).

`CLAUDE.md` says the old connector/webhook framework was removed on purpose. This design is therefore not a connector
framework: it is one push route, one registry that is code-reviewed, and no outbound calls.

### What Vulcan and Grim expose (research, 2026-11-02)

| Fact | Status | Source |
| --- | --- | --- |
| Vulcan exposes `VulcanFlagEvent` (flag event, cancellable via `setCancelled`, called async) | Named in changelog; getter names **not verified** | Vulcan resource updates, <https://speedgot.ajg0702.us/resources/83626> and its `/updates/` pages |
| Vulcan `VulcanPostFlagEvent` fires when an alert is sent, not on every violation (unless configured) | From a changelog entry of a 2.0.0 pre-release; exact release **not pinned** | same |
| Vulcan `CheckData` system, `getMinimumVlToNotify()`, `getAlertInterval()`, `VulcanAPI#toggleAlerts` | From changelog; signatures **not verified** | same |
| Vulcan has no fetched official developer docs, so check name, type, VL, server, ping, TPS getter names are **unverified** | Must be confirmed by the bridge change against the Vulcan jar | none found |
| Grim: `GrimAbstractAPI` via Bukkit services manager, `bus.get(FlagEvent.class).onFlag(grim, (user, check, verbose, cancelled) -> ...)` | Verified from the docs page | <https://grimdocs.pages.dev/api.html>, <https://github.com/grimanticheat/grimapi> |
| Grim `FlagEvent` handler: `event.getPlayer()` (a `GrimUser`), `event.getCheck()` (`AbstractCheck#getCheckName()`) | Verified from the docs page | <https://grimdocs.pages.dev/api.html> (last modified Feb 2024, may lag the channel API) |

The common shape of both is: player, check name, a free verbose string, cancelled state, a server, and (from the
server side) ping and TPS as context. The envelope and the `vulcan/DETECTION` v1 schema are built from that shape.
Anything that the bridge change cannot confirm is dropped from the schema, not guessed.

## Goals / Non-Goals

Goals: an integration can deliver a typed detection safely; staff see it with the right trust wording; a new
integration works with the generic renderer on day one; nothing goes into the ELO engine or a punishment on its own.

Non-goals: see `proposal.md` (no bridge, no automatic punishment, no remote components, no publication).

## Decision 1: Ingestion

**Chosen.** A small bridge plugin per game-server platform (the Vulcan bridge is the first) registers as a server-side
listener of the anticheat's events. It buffers detections, and posts batches over HTTPS to the backend
`POST /integration/detections`, authenticated with its own Entra app registration (OAuth2 client credentials,
token audience = the API audience, app role `Blackhole.Integration`).

- **Identity is the registration, not the body.** The backend reads the token's client id (`azp`/`appid`), looks up
  an enabled `integration` row with that client id, and uses its `sourceSystem`. A body field `sourceSystem` is
  accepted only if it equals the registration's value, otherwise the item is `REJECTED` with `SOURCE_MISMATCH`.
  This is what makes "`vulcan` says a thing about `grim`" impossible.
- **Two gates.** The role `Blackhole.Integration` (Entra app role assignment) and the registration row. Either
  missing → 403. Revoking a bridge is disabling its registration (immediate) or removing the app role (at the next
  token).
- **REST, not NATS, for ingestion.** The bridge needs a synchronous per-item answer (schema rejected vs duplicate vs
  accepted) to decide what to retry. NATS stays the outbound event bus. A later decision may add a NATS subject if
  many integrations appear; that is not needed now.
- **Batching.** The bridge sends at most 50 items per request and at most 64 KiB per body, flushes every 2 seconds or
  when a batch is full. Larger bodies are answered 413 without parsing.
- **Idempotency.** Each detection carries `detectionId`, a UUID the bridge generates once per detection and keeps for
  retries. The unique key is `(integration_id, detection_id)`. A repeated item answers `DUPLICATE` and writes nothing.
  The bridge retries a whole batch on a network failure; the per-item answers make that safe.
- **Retry and buffer when Blackhole is down.** The bridge holds a bounded in-memory queue (default 5 000 detections
  or 8 MiB, whichever is first). It retries with exponential backoff (1 s to 60 s). When full it drops the **oldest**
  detection and increments a counter `blackhole_integration_dropped_total` on the game server's own metrics. It logs
  the drop with the check name and count, never the player. No disk buffer: a file on the game server would be a
  second, unmanaged copy of personal data. Delivery is therefore at-most-once under long outages; the design accepts
  that a detection is a signal and the anticheat keeps its own log.
- **Timeouts.** Connect 2 s, request 5 s, no retry within a single request beyond the batch loop. The bridge never
  blocks the game thread: events are copied into the queue, the network runs on its own thread.
- **Clock.** `occurredAt` must be within `[now - 24h, now + 5min]`. Outside → `REJECTED` `OCCURRED_AT_OUT_OF_RANGE`.
  The server clock is the only time source for the staff timeline (the backend stores `received_at` too).

### Registration granularity

Registrations are Entra app registrations, and their granularity follows how the server runs. Maintainer decision,
2026-11-02, recorded per case:

| Server kind | Credential | Recommendation | Why |
| --- | --- | --- | --- |
| Dynamic CloudNet services (`Lobby-1`, `Lobby-2`, started and stopped by a task) | One Entra app per CloudNet **task** (platform/group, e.g. `Lobby`), delivered by the task's template | **Per task** | Instances come and go. An app registration per instance would mean creating and deleting Entra apps with every start and stop, which is not operable. A new instance gets the task's credential from its template. |
| Static, long-lived servers (`Survival-1`, `Creative`) | One Entra app **per instance** | **Per instance** | Few, long-lived and revocable on their own. A compromised static server loses only its own rights. |
| A task that also runs a static server | Keep the static server out of the task group; it gets its own registration | **Per instance** | Avoids a task credential that covers a server meant to be individually revocable. |

**Trade-off: blast radius against operability.** A task credential is one secret for many instances. A leaked task
secret lets an attacker submit detections as any instance of that task. It cannot read data, and it cannot submit for
another task or for a static server. Mitigations: the registration lists the tasks and static servers it may report
for; disabling the registration cuts the whole task at once; rotating the secret is one change in the task template.
A per-instance credential would shrink the blast radius to one instance, at the cost of an app registration per start.
The network accepts the task-level radius for dynamic services and keeps per-instance credentials for everything
long-lived.

**Server label.** The envelope's `server` names the concrete instance (`Lobby-2`). It is **reported, not trusted for
authorization**. The backend maps the token's client id to the registration, and the registration lists the allowed
tasks (`allowedTasks`, e.g. `Lobby`) and static servers (`allowedStaticServers`, exact names). A CloudNet service name
is `<task>-<n>` with `<n>` digits, so `Lobby-2` belongs to task `Lobby` and `Lobbyx-2` does not. A label outside the
registration's scope is `REJECTED` with `SERVER_OUT_OF_SCOPE`. That check guards against misconfiguration (a `Lobby`
bridge reporting `Survival-1`). It does not stop a compromised credential, which can report any label its registration
allows. The label is shown to staff as reported.

Registration fields: `{sourceSystem, entraClientId, enabled, allowedKinds, allowedTasks, allowedStaticServers}`.

Rejected alternatives:

- *Velocity/proxy plugin path.* The anticheat runs on the game servers; the proxy sees neither its flags nor its
  ping/TPS. Rejected.
- *Anticheat posts to Blackhole directly.* Vulcan has no HTTP sink and no plugin of ours runs in its process; the
  bridge is the only code that can map its events to the contract. Rejected.
- *NATS publish from the bridge.* Gives no per-item answer, makes the bridge a NATS client with its own credentials
  and subject ACLs, and lets an integration publish into the domain stream. Rejected for ingestion; the bus is for
  backend-internal fan-out.
- *Shared secret per platform instead of Entra app registrations.* No identity per integration, no revocation per
  bridge, and the secret would be a second credential scheme next to Entra. Rejected.
- *Disk buffer on the game server.* A second copy of personal data outside retention control. Rejected.
- *Webhook from Blackhole to the integration.* Caller-controlled URLs and SSRF surface, and no need: the integration
  pushes. Rejected.

## Decision 2: Data model

### Envelope (the same for every kind)

| Field | Type | Rule |
| --- | --- | --- |
| `detectionId` | UUID | required; idempotency key with the integration id |
| `kind` | string | required; from the integration's allowed kinds, e.g. `DETECTION` |
| `schemaVersion` | int | required; 1 for the first version; must have a registered schema |
| `occurredAt` | ISO-8601 instant | required; window rule above |
| `server` | string 1..64 | required; the section label, for example `survival-2`; never an IP or host name |
| `owner` | string | required; the Minecraft UUID as sent over TLS; the backend derives the owner hash (the same keyed hash the punishment API uses) and never persists the raw UUID |
| `severity` | enum `INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` | optional; reported by the integration, shown as "reported severity" |
| `summary` | string 1..200 | required; plain text, a one-line description for lists |
| `payload` | object | required; validated by the registered schema for (`sourceSystem`, `kind`, `schemaVersion`) |

`sourceSystem` is not in the body; it comes from the registration (decision 1).

### Validation library

Chosen: **`com.networknt:json-schema-validator:2.0.8`**.

- **Licence:** Apache License 2.0 (POM: https://repo1.maven.org/maven2/com/networknt/json-schema-validator/2.0.8/json-schema-validator-2.0.8.pom).
- **Age:** uploaded to Maven Central on 2026-10-02 (listing: https://repo1.maven.org/maven2/com/networknt/json-schema-validator/2.0.8/),
  more than two weeks before 2026-11-02.
- **Draft:** Draft 2020-12 is supported. The README at tag 2.0.8 lists "draft V4, V6, V7, V2019-09 and V2020-12"
  (https://github.com/networknt/json-schema-validator/tree/2.0.8). Every schema file declares 2020-12 in `$schema`.
- **Jackson:** depends on Jackson 2 (`jackson-databind`). The backend already uses Jackson 2 through Micronaut
  (`mn.jackson.databind` in `backend/build.gradle.kts`), so no second JSON stack is added.
- **Rejected for now: `3.0.8`** (uploaded 2026-09-30, Apache-2.0, Java 17+, https://github.com/networknt/json-schema-validator).
  It requires Jackson 3 next to the Micronaut Jackson 2 stack, a second JSON stack for one validator. Revisit when the
  backend moves to Jackson 3.
- Other libraries were not compared in depth. The PR that adds the dependency re-checks the version age that day.

### Kind schemas

- Stored as JSON Schema files in `backend/src/main/resources/integration-schemas/<sourceSystem>/<kind>/v<n>.json`,
  loaded once at startup into an immutable `SchemaRegistry`. A schema is a code change, reviewed like any other.
- Every schema sets `additionalProperties: false`, `maxLength` on every string, numeric bounds, and `required`.
- A missing schema for a `(sourceSystem, kind, schemaVersion)` triple is `REJECTED` `UNKNOWN_KIND`.
- The validator also enforces: payload at most 4 KiB serialised, nesting depth at most 3, no strings containing a
  UUID or IPv4/IPv6 literal (the existing `PiiGuard` is reused), no control characters.
- Schema `v1` for `vulcan/DETECTION` (**draft; fields confirmed or dropped by `add-vulcan-bridge`**):

```
check            string  1..64   the check name as Vulcan reports it, for example "KillAura"        (unverified name)
checkType        string  0..32   optional sub-type / letter, when the anticheat has one             (unverified)
violationLevel   number  0..10000 optional; VL after this flag, null when the anticheat version does not report it
ping             int     0..60000 ms, optional; null when the server or bridge cannot sample it
tps              number  0..20.5, optional; null when the server or bridge cannot sample it
experimental     boolean optional; null when the anticheat version does not report it
detail           string  0..300 optional; the anticheat's verbose text, plain text
```

  Only `check` is required. Every other optional field is nullable (`type` lists `null` as well) and may be absent.
  A payload with missing optional fields is `ACCEPTED`; the card shows "not reported" for a null field. This is what
  lets one schema serve several Vulcan versions (decision 7).

  No coordinates, no world name, no inventory, no chat. Grim's shape maps to `check`, `detail` and `cancelled` (which
  would be a `grim/DETECTION` v1 later, not in this change).

### Tables (migration `V<n>__integration_detections.sql`)

`n` is the next free Flyway version on `origin/main` at implementation time (`backend/src/main/resources/db/migration`;
V42 is the newest today, so V43 is the next). The file is appended; an old migration is never edited.

- `integration`: `id` (bigint), `source_system` varchar(64) unique, `entra_client_id` varchar(64) unique, `display_name`,
  `enabled` boolean, `allowed_kinds` text (comma list of `kind:version`), `created_at`, `created_by` (oid of the admin).
- `integration_detection`: `id`, `integration_id` FK, `detection_id` uuid, `kind`, `schema_version` smallint,
  `owner` char(128) (hash), `server` varchar(64), `occurred_at`, `received_at`, `severity` nullable, `summary`
  varchar(200), `payload` jsonb (validated), `attached_evidence_id` nullable. Unique `(integration_id, detection_id)`.
  Index `(owner, occurred_at desc)` for the player page; index `(received_at)` for the sweeper.
- `evidence_items.kind`: a new value `INTEGRATION` at the next ordinal (TRANSCRIPT is 5, so 6), with the CHECK constraint
  widened in the same migration. Existing rows are untouched.
- Case event `detection.attached` (the `case_event` type list) with `payload_version` 1.

### Retention and erasure

- `integration_detection` rows are personal data (owner hash, server, check, detail, ping and TPS). Retention is
  **180 days** from `occurred_at` by default, set by `BLACKHOLE_INTEGRATION_DETECTION_RETENTION` (ISO-8601 duration,
  default `${BLACKHOLE_INTEGRATION_DETECTION_RETENTION:P180D}`), documented in `application.yml`, enforced by
  `IntegrationDetectionSweeper`.
- **Why 180 days (maintainer decision, 2026-11-02).** An appeal can arrive long after a punishment, and a
  repeat-offender check needs earlier flags that were never attached. Thirty days would drop them before anyone could
  look. The legal basis stays legitimate interest for network security and abuse prevention (GDPR Art. 6(1)(f)), and the
  operator's retention period is documented as principle 1 requires.
- Attached detections become `evidence_items` rows and follow the **evidence** lifetime (the punishment record), not
  the 180-day window. The sweeper never touches them.
- **Erasure on request.** The existing erasure-by-owner path is extended so one request deletes the owner's unattached
  detections, and the attached copies go through the evidence erasure path (reason `ERASURE`). One audit entry covers
  the request.
- Detections are never published: no event carries payload, check or detail, and logs carry ids and counts only.

### Attaching to a punishment or a case

- **Staff picks.** No automatic attach. The player page "Detections" tab lists unattached detections of the owner,
  grouped by check, with a multi-select "attach to case" action (the case is found by the punishment identifier or
  short id, as today).
- An attach writes one `evidence_items` row (kind `INTEGRATION`, `sourceSystem`, the validated payload as the snapshot,
  `summary`) and sets `attached_evidence_id`. Attaching the same detection twice is a no-op.
- The case timeline gets one `detection.attached` event (`evidenceId`, `sourceSystem`, `kind`, count of detections).
  Staff-only; not in `PORTAL_VISIBLE_TYPES`. No check name, no detail.
- Pending evidence (before a punishment exists) is not used for detections in this change; attach needs a case.
- An open case of an owner with detections newer than the staff member's last view of that case shows a count in the
  inbox. The count is a query, not a stored signal.

### Event and API surface

- `POST /integration/detections` (role `INTEGRATION`): batch in, per-item result out.
- `GET /integration/registrations` and `POST /integration/registrations`, `PATCH .../{id}` (enable/disable, allowed
  kinds): role `ADMIN`. Each write is audited (`integration.registered`, `integration.updated`,
  `integration.disabled`) with the actor's oid and no secret; the Entra client id is stored, the secret never is.
- `GET /players/{owner}/integration-detections` and `POST /cases/{id}/integration-detections:attach`: role `MODERATOR`
  (read and attach). `INTEGRATION` role has no read route.
- No domain event in this change. Future consumers can subscribe to `evidence.attached` (existing).
- OpenAPI: `IntegrationDetectionApi` interface, sealed `IntegrationDetectionDTO` marker with `Request`/`Response`/`Error`
  variants per the `micronaut-dto-contract` convention, client spec bump.

## Decision 3: Custom UI components

### Renderer registry (repository code only)

- `web/src/components/integration/registry.ts` maps `"<sourceSystem>/<kind>@<schemaVersion>"` to an async import of a
  component in the same folder. Each entry is a dynamic `import()`, so Vite emits one chunk per integration. The
  registry is a plain object literal in reviewed code; it is not fetched from the API.
- **Lookup.** Unknown key → `GenericDetectionTable.vue`. The generic renderer shows the validated payload as a
  `DefinitionList` (existing component): labels from the schema's `title` fields, values as text, numbers through
  `Intl.NumberFormat` for the active locale, timestamps through the locale-aware formatter. It works for any kind.
- The API returns `payload` already validated, and the schema title/label map is served with the registry metadata
  (`GET /integration/kinds`), so the generic renderer needs no per-kind code.

### Components

- `VulcanDetectionCard.vue` for `vulcan/DETECTION@1`. Layout (text sketch):

```
+--------------------------------------------------------------------------+
|  Vulcan  ·  Detection                                    [ reported: HIGH ]|
|  Reported by Vulcan (integration). A signal, not a verdict: staff decide.|
|--------------------------------------------------------------------------|
|  Check        KillAura (experimental)                                    |
|  Violation    VL 14   ▁▁▂▂▃▅▆█  (last 24 h, this check)                  |
|  Server       survival-2                                                 |
|  Time         02 Nov 2026, 14:32 (server clock, 24 h window)             |
|  Context      ping 48 ms · TPS 19.8                                      |
|  Detail       "Attack angle delta above threshold" (plain text)          |
|--------------------------------------------------------------------------|
|  [ Attach to case… ]                              Source: Vulcan 1.x      |
+--------------------------------------------------------------------------+
```

  - The VL chart is an inline `<svg>` of the same check's VL values for this owner over the window, with
    `role="img"` and a visually hidden `<table>` carrying the same numbers. Values never depend on colour alone.
  - Ping and TPS are labelled as context, not as proof.
  - `detail` is text. It is shown in a `<p>` with a `max-height` and "show all".
- `GenericDetectionTable.vue` for everything else.

### Where components appear

| Place | Shows | Component |
| --- | --- | --- |
| Evidence list (`components/evidence`) for `INTEGRATION` items | The attached detection with its trust line | registry lookup |
| Case timeline | One line: "Detection attached (Vulcan, 1 item)". No card, no check name | timeline entry (text only) |
| Player page, "Detections" tab | Unattached and attached detections, grouped by check | registry lookup per row, attach action |
| Inbox | Count of new detections on an open case | count only |

### Remote components rejected

Third-party bundles are rejected: they would run inside the staff session with the staff token in scope, they
would need `script-src` relaxed (the panel's policy is `script-src 'self'`), and they would make every integration a
code-execution path in the staff panel. Integrations contribute **data** and a **schema**; the component is written
and reviewed here. A new integration's first detection is rendered by the generic table, which is the designed fallback.

### Trust and wording

- Every integration element names the source ("Reported by Vulcan"), carries "A signal, not a verdict: staff decide",
  and shows the reported severity as "reported", never as Blackhole's own.
- No element uses the words "cheating", "hacked" or "guilty". The check name is shown as the anticheat's own term.
- The attach action is labelled "Attach to case", not "Punish".

### Accessibility and i18n

- Keyboard: the card is a `<section>` with a heading; the attach action is a button with a visible label; the chart is
  not focusable (its table alternative is).
- Colour is never the only signal (severity has text; VL has numbers; the chart has a table).
- Contrast uses the Material 3 tokens of the admin UI. Dark mode follows the existing theme.
- All labels, the trust line and the chart description are message keys in `de` and `en` with the existing parity test.
  The check name, detail and summary come from the integration and are shown verbatim (they are data, not strings to
  translate).
- Numbers and times use the active locale.

## Decision 4: Trust and wording (summary)

A detection is a signal. The UI states who reported it, the reported VL or severity, and that staff decide. Nothing in
this change moves ELO, starts an appeal, or changes a punishment. An integration cannot mark a detection as "confirmed";
only a staff attach with a case does anything, and it does not change the verdict.

**Detections MUST NOT feed the ELO engine or create, change or decide a punishment or an appeal.** This is a
requirement of this change (see the spec), not an open question. Any future use needs an amendment of principle 4 in
`openspec/config.yaml` first, and then its own change. Appeal reviewers see attached integration evidence as evidence
(decision 2); that is not a verdict input of its own.

## Decision 5: Security and ASVS notes

- **Authentication.** Entra token, app role `Blackhole.Integration`, registration lookup. No secret in the backend. The
  bridge's secret is held by the game server operator (environment variable or the server's secret store), rotated
  in Entra. The backend never sees it.
- **Least privilege.** The integration role has one route. It cannot read players, punishments, evidence or other
  integrations' detections. An integration can only write `sourceSystem` = its own.
- **Input validation.** Schema-driven, closed objects, length caps, payload size cap, body size cap, batch cap,
  time window, PII guard. Unknown fields are rejected, not ignored.
- **Output.** Text only. No `v-html`, no markdown, no links from integration text (a `detail` containing a URL is shown as
  text, never as a link; there is no SSRF surface because nothing fetches). The existing `ChatMarkdown` renderer is not used.
- **Logging.** Ids, counts, reason codes. Never payload, summary, detail or owner. Audit entries for registration
  changes carry no secret.
- **Rate limits.** At the infra layer (principle 5), with a limit per integration client id at the edge. The app adds
  only the size and batch caps above.
- **Abuse by a compromised bridge.** It can flood detections for any owner. Mitigations: the per-integration
  registration can be disabled at once; detections are staff-only and never auto-act; the sweeper bounds storage;
  the inbox count shows a spike to staff. Accepted residual risk, recorded here.
- **Dependency.** The schema validator is `com.networknt:json-schema-validator:2.0.8` (see "Validation library"). It is
  added in PR 2 of decision 6.

## Decision 6: Phasing (small PRs, conventional titles)

This change ships as `feat(integration)` in the PRs below; all carry that type and scope. The Vulcan bridge plugin is a
separate proposal (`add-vulcan-bridge`, `feat(vulcan)`), because a change that mixes types or scopes is two changes.

1. `feat(integration): add integration registry and machine principal` - role, registration API (with `allowedTasks`
   and `allowedStaticServers`) and audit, migration for `integration` and `Roles.INTEGRATION`. Tests first.
2. `feat(integration): ingest detections with schema validation` - schema registry (networknt 2.0.8), envelope,
   batch route, idempotency, server-scope check, `integration_detection`, retention sweeper (180 days by default),
   `vulcan/DETECTION` v1 schema with optional fields, `POST` route.
3. `feat(integration): attach detections as evidence` - `evidence_items` kind `INTEGRATION`, attach route, timeline
   event, inbox count.
4. `feat(integration): render detections in the staff panel` - registry, generic renderer, evidence/timeline/player
   tab hooks, client spec bump.
5. `feat(integration): add the Vulcan detection card` - `VulcanDetectionCard` and its chart, i18n keys.

PRs 2 and 4 are the first ones a bridge can exercise. The bridge change depends on PR 2.

## Decision 7: Vulcan support is version-independent

Vulcan's API is unverified (see the table in "What Vulcan and Grim expose") and it changes between releases. The
maintainer decided (2026-11-02) that the bridge MUST work across Vulcan versions. The bridge therefore never assumes
one version:

- **Runtime detection.** `VulcanEventAdapter` probes at plugin enable which flag event class exists (by name, through
  the plugin's classloader, so no compile-time reference breaks on a version without it) and which getters exist
  (reflection, resolved once into method handles). Every probed feature is recorded as available or missing.
- **Field mapping.** `check` comes from the check-name getter, `violationLevel` from the VL getter, and `checkType`,
  `experimental` and `detail` from their getters when present. `ping` and `tps` come from the server API (Paper), not
  from Vulcan, and are null when that API lacks them. A missing optional field is sent as null (the schema allows it).
  A flag without a `check` is dropped and counted as `blackhole_integration_dropped_total{reason="unmapped"}`, because
  `check` is the one required field.
- **Never crash the server.** The probe and the mapping run inside a guard. Any throwable disables the Vulcan
  integration for the rest of the run and logs once. The flag event handler never throws and never blocks: it copies
  the fields into the queue. Vulcan's flag event is async (changelog), so ping and TPS are sampled on the main thread
  through the scheduler; if the sample is not ready, the field is null, and the handler does not wait for it.
- **Log once.** At enable, one INFO line lists the detected features and the missing ones, for example
  `vulcan bridge: features check, vl, detail; missing checkType, experimental; ping and tps from the server api`.
  Nothing is logged per flag.
- **Unknown version.** If no flag event class exists, the bridge stays idle, logs one WARN ("flag event not found,
  bridge idle") and sends nothing. It does not guess a mapping.
- **Tested against at least two API versions.** The bridge change ships test doubles for two versions, modelled on the
  public changelog names and labelled as doubles (not Vulcan's real signatures): one with the full getter set, one
  with some getters renamed or absent. Where the real jars of two releases can be downloaded, the same tests run against
  them as fixtures, and the tested versions are named in the bridge proposal. Both versions must map the shared fields
  to the same envelope.

### Follow-up note: `add-vulcan-bridge`

A separate proposal with scope `vulcan`, written after this change is merged. Its content is fixed here so the two
stay consistent:

- A Paper/Folia plugin per game server, configured with its CloudNet service name (the `server` label), its Entra
  client id and secret (delivered by the task template), the backend URL and the queue bounds.
- The adapter of decision 7, with the tests listed there. Nothing merges until both version doubles pass.
- It ships `vulcan/DETECTION@1` from this change. A changed field means `@2`, never an edit of `@1`.
- It owns the operator documentation for credential setup: one registration per task for dynamic services, one per
  instance for static servers (decision 1).
- It never feeds ELO or punishments (decision 4).

## Risks / Trade-offs

- **Vulcan's event API is not verified and changes between versions.** Mitigation: the runtime adapter of decision 7,
  optional schema fields, and tests against two versions in the bridge change. A field change becomes `@2`; rows keep
  their version.
- **At-most-once delivery under outage.** Accepted; detections are signals, and the anticheat logs its own history.
- **Unattached detections are kept 180 days.** That is personal data kept for a legitimate purpose, bounded by the
  sweeper and by erasure on request (decision 2).
- **Generic renderer shows raw check names.** Accepted; the generic view is a fallback and says so.

## Migration Plan

Additive: new tables, a new enum value, a new role mapping. Nothing existing changes meaning. Rollback = disable the
integration registrations (no code path writes without one) and, if needed, the feature flag is the empty registry.
Migration is append-only.

## Open questions

None. The maintainer answered the six questions of 2026-11-02, and each answer is in the design: registration
granularity (decision 1, "Registration granularity"), retention of 180 days (decision 2), appeal reviewers seeing
attached evidence (spec requirement), the validation library (decision 5, "Validation library"), version-independent
Vulcan support (decision 7), and the ELO and punishment prohibition (decision 4, spec requirement).
