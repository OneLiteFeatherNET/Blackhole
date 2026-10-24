# Design

## Context

- `POST /public/appeal` (`PublicAppealService.file`) validates the statement, reads the appeal state and files through
  `AppealEligibilityService.fileIfEligible`. Errors are `PublicError` variants sent as `PublicErrorBody {code, availableFrom?}`.
- Every `/public/**` route except login needs a player session; the portal's backend-for-frontend forwards an allow-list
  of them (`portal/server/lib/bff.ts`, `portal/src/api/contract.ts`).
- Staff routes use `@Secured(Roles.X)`; `RouteCoverageIntegrationTest` and `PermissionMatrixIntegrationTest` pin every route.
- `@Transactional` is unusable; a version is created by one data-modifying statement.

## Decisions

### 1. Storage: append-only table, version is a plain integer

`appeal_conditions(version integer primary key, body text not null, created_at bigint not null, recorded_by_oid uuid)`.
Rows are only inserted. Version 1 is the default text, inserted by the migration. A save inserts
`max(version) + 1` in one statement (`INSERT ... SELECT coalesce(max(version),0)+1 ...`); the primary key makes a
concurrent save fail on one side, which is answered 409 `CONFLICT` so the editor retries from the fresh version.
`recorded_by_oid` is the verified actor (`ActorResolver`), like other staff writes.

### 2. Text format: plain text with paragraphs and bullet lists

No Markdown library. The body is plain text; a blank line separates paragraphs and a line starting with `- ` is a bullet.
The portal splits the text itself and renders every part through Vue text interpolation, so no `v-html`, no raw HTML,
and a pasted `<script>` is shown as literal text. The existing portal test that forbids markup sinks gets a case for this.
Limits: 1 to 4000 characters, no control character except newline, trimmed, `\r\n` normalised. The backend stores what it
accepts; it does not render.

### 3. Filing check

After the statement is valid and the existing refusals (`FORFEITED`, `FROZEN`, `NOT_ELIGIBLE`) are evaluated, filing compares
`conditionsVersion` from the body with the current version. A missing, older or newer number answers 409
`CONDITIONS_CHANGED`; the portal then reloads the text and asks again. Placing the check after the state refusals keeps those
answers unchanged. The accepted version is written to `appeals.conditions_version` (nullable integer, `NULL` for appeals filed
before this change) inside the same save. Nothing else is recorded: no time of acceptance beyond the appeal's own `createdAt`.

### 4. Routes

| Route | Role | Notes |
|---|---|---|
| `GET /appeal-conditions` | `VIEWER`+ | current `{version, text, createdAt}` |
| `GET /appeal-conditions/versions` | `VIEWER`+ | all versions, newest first |
| `POST /appeal-conditions` | `ADMIN` | body `{text}`, creates the next version, answers it; `PROXY` and `DISCORD` denied |
| `GET /public/appeal/conditions` | player session | `{version, text}`, `no-store`, any scope |

The public route needs a session like the others: the form is only reachable after the Ban ID login, so there is no new
anonymous route and `POST /public/session` stays the only one. A staff edit does not publish a domain event.

### 5. Layering

New package `appealconditions/` with `service/AppealConditionsService` (staff reads and the save), an `AppealConditionsStore` seam with a
JDBC implementation, sealed `AppealConditionsDTO` (`CreateRequest`, `Response`, `Error`), `controller/AppealConditionsApi` and a thin
controller. The public route is added to `PublicApi` / `PublicController` and answered by a method of `PublicAppealService` that reads the same
store. `AppealEntity` gains a nullable `conditionsVersion` field.

### 6. Migration

`V19__appeal_conditions.sql` (next free version on `main`; V18 is the Discord ticket migration): creates the table, inserts version 1 with
the default text, and adds `appeals.conditions_version`. Existing appeals keep `NULL`. No data is rewritten.

### 7. Panel

A route `/appeal-conditions` under the admin rail (view for every role, textarea editor and "Save as new version" only for `ADMIN`),
following the sections/templates pages: fake-client tests, de/en strings, `i18n.keys.test.ts` parity, role gating test.
The editor shows the current version number, the character counter and a version history list; saving never overwrites.

### 8. Portal

`AppealFormView` loads `GET /public/appeal/conditions`, renders the text, and keeps the file button disabled until the checkbox is ticked;
the request carries the loaded `conditionsVersion`. On 409 `CONDITIONS_CHANGED` the form reloads the text, unticks the box and says
the conditions changed. `ConversationView` shows a collapsed "Appeal conditions" block (native `details`) at the top. The BFF allow-list and
`contract.ts` gain the route. The English strings live in `portal/src/i18n.ts` (the portal is English only for now).

### Default text (version 1)

```
Appeal conditions

- Be respectful. Insults and threats towards the team can end your appeal.
- You may file one appeal per Ban ID.
- Sharing your Ban ID, or a screenshot of it, forfeits your right to appeal.
- After a rejection you have to wait before you can appeal again. The date is shown to you.
- The team decides, and the decision is final for this appeal.
- We cannot promise how fast we answer.
- Do not open the same appeal through Discord and through the portal.
- Do not write personal data such as addresses, or details about other people, in your messages.
```

## Risks

- A save racing another save: one fails on the key and the editor reloads. Acceptable for a rarely edited text.
- A player who filled the form for long finds the version changed: they confirm again; cheap and intended.
