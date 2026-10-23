## 1. Backend: issue and confirm link codes (PR 1)

- [ ] 1.1 Write the failing JUnit tests first (injected `Clock`, self-signed JWTs via `TestJwtIssuer`, no sleeps) for code issue, confirm success, expired, reused, wrong, one-to-one, unlink and anonymize; verify they fail for the right reason
- [ ] 1.2 Add Flyway `V10__staff_account_link.sql` (use the next free number at apply time): `staff_link_code`, `staff_link`, nullable `actor_oid` on `punishments`, `reports`, `evidence_items`, `evidence_audit`; verify on the scratch-DB Flyway technique and that no applied migration changed
- [ ] 1.3 Add `StaffLinkCodeGenerator` (8 chars Crockford base32 from `SecureRandom`, normalisation of input, hash), entities and repositories in the `security` package; verify with unit tests for alphabet, length, normalisation and hash-only storage
- [ ] 1.4 Add `StaffLinkService` (issue, confirm, status, unlink, list, lookup by Minecraft UUID) returning the sealed `StaffLinkDTO` Error variant for expected failures, no `@Transactional`; verify the one-to-one and replace-with-audit scenarios
- [ ] 1.5 Add `StaffLinkController`/`StaffLinkApi` and `AdminStaffLinkController`/`AdminStaffLinkApi` with `@Secured` per the permission matrix; extend `PermissionMatrixIntegrationTest` and the matrix table; verify `RouteCoverageIntegrationTest` is green
- [ ] 1.6 Resolve `actor_oid` from the live link when the proxy sends `onBehalfOf` and write it on punishments, reports and evidence; expose `actorOid` and `actorName` on the punishment DTO; verify an in-game punishment of a linked and of an unlinked UUID
- [ ] 1.7 Extend `AppealDecisionService` self-review (reviewer `oid` against `source`, `recorded_by_oid`, `actor_oid` and the current link of `source`); verify an in-game ban cannot be decided by its linked issuer and another moderator can
- [ ] 1.8 Make `StaffActorService.anonymize` remove the link; publish `staff-link.created`/`staff-link.removed` with the `oid` only; verify logs and events never contain the code
- [ ] 1.9 Add `blackhole.security.staff-link.*` to `application.yml` (`BLACKHOLE_STAFF_LINK_TTL`) with explanatory comments; regenerate the OpenAPI spec to `client/specs/blackhole-api-0.0.16.yml`, point `client/build.gradle.kts` at it and verify `./gradlew :client:openApiGenerate :backend:test` passes

## 2. Velocity: /blackhole link (PR 2, based on PR 1)

- [ ] 2.1 Write failing tests for the command with a fake backend client and a fixed `Clock`: success, invalid or expired code, already linked, console sender refused, missing permission
- [ ] 2.2 Add `LinkCommand` (`/blackhole link <code>`) and `WhoamiCommand` (`/blackhole whoami`) registered through the existing Cloud setup, permissions `blackhole.link`, player-only; verify the code is never logged
- [ ] 2.3 Add the `staff.link.*` and `staff.whoami.*` message keys in `blackhole_en.properties` and `blackhole_de.properties` in the `bh:` style; verify the key-parity test and the console rendering
- [ ] 2.4 Verify against the real stack (compose backend, mock OIDC): issue a code via the API, confirm in game, see the attribution of a following `/ban`

## 3. Web: account dialog (PR 3, based on PR 2)

- [ ] 3.1 Regenerate the web API client from spec 0.0.16 (`generate:api`); verify the build
- [ ] 3.2 Write failing component tests (fake clock, fake API) then add `AccountLinkDialog.vue` opened from the top bar: status, issue button, code with countdown (`aria-live` off while ticking, announced at expiry), copy button with confirmation, unlink with confirmation dialog; verify keyboard operation and focus return
- [ ] 3.3 Add the admin list of links (ADMIN only) with unlink; show `actorName` on the punishment timeline; verify the role-aware visibility
- [ ] 3.4 Add de and en strings and verify `i18n.keys.test.ts` parity and the no-native-select test

## 4. Documentation (PR 4, based on PR 3)

- [ ] 4.1 Document linking in `README.md` (flow, permission nodes, the operator rule to grant `blackhole.link` only to staff groups, retention statement still pending legal confirmation, the link as staff personal data) and the operator guide; verify the retention paragraph still says it is pending
- [ ] 4.2 Update `.claude/rules/security.md` and the matrix reference for the new routes; verify `openspec validate --all --strict`

## 5. Pull requests

- [ ] 5.1 Open the stacked pull requests, each based on the previous branch, with the titles `feat(auth): issue and confirm staff account link codes`, `feat(velocity): link a staff minecraft account with a code`, `feat(web): link the minecraft account from the admin ui` and `docs(auth): document staff account linking`; verify CI is green on each and commitlint accepts the titles
