## Ordering

Three PRs, stacked in this order. The two app PRs are inert until the chart sets their variables,
so they can merge first. Within each PR, write the failing test first (red), then the code (green),
then tidy (refactor). Every test uses an injected clock value and no system time, no network, and no
shared state, per the F.I.R.S.T. rules in the Blackhole CLAUDE.md.

## 1. PR 1: `feat(portal)` (branch `feat/portal-security-txt`)

- [ ] 1.1 Add `portal/server/lib/security-txt.test.ts` (Vitest): the parser rejects an invalid `BLACKHOLE_SECURITY_CONTACT`
  (not `mailto:`, `tel:` or `https:`), an `Expires` without a time zone, an `Expires` in the past, an `Expires`
  more than one calendar year ahead, and a contact without `Expires`. Each case names one behaviour, and each
  failure message names the variable. Use a fixed `now` value.
- [ ] 1.2 Extend the same test file: the parser accepts a valid block, emits `Canonical` from
  `BLACKHOLE_PORTAL_ORIGIN`, emits `Policy`, `Encryption` and `Acknowledgments` only when set, and defaults
  `Preferred-Languages` to `en, de`.
- [ ] 1.3 Add `portal/server/routes/well-known/security-txt.test.ts` (or the handler test in `lib/handlers` tests):
  HTTP 200 with `text/plain; charset=utf-8`, `Cache-Control: public, max-age=86400`, the security headers, and no
  call to the backend (the fetch spy is never called). Also a 404 when no contact is configured. Run it red first.
- [ ] 1.4 Confirm with a failing test that Nitro routes `/.well-known/security.txt` ahead of the `[...]` SPA
  fallback. If dot-directories are not routed, register the exact path from a plugin instead (design D4).
- [ ] 1.5 Implement `portal/server/lib/security-txt.ts` (pure parser and renderer, clock passed in) and the route
  `portal/server/routes/.well-known/security.txt.ts` (or the plugin from 1.4). Wire the startup check into
  `lib/startup.ts` in the same style as `loadPortalConfigOrExit`. Make the tests from 1.1 to 1.3 green.
- [ ] 1.6 Refactor and check the BFF: no entry in `lib/bff.ts` `ROUTES`, and `server/api/[...path].ts` still answers
  404 for anything outside the allow-list.
- [ ] 1.7 Amend `openspec/config.yaml` principle 2 with the exemption `GET /.well-known/security.txt` and the
  reason, in its own commit `docs(openspec): exempt the security.txt route from the token rule`.
- [ ] 1.8 Run `npm test` and `npm run build` in `portal/`. Commit `feat(portal): serve an rfc 9116 security.txt`
  (lower case, no trailing period, Claude-Session trailer) and push the branch. The PR is opened in 4.3.

## 2. PR 2: `feat(web)` (branch `feat/web-security-txt`, from `origin/main`)

- [ ] 2.1 Add `web/server/lib/security-txt.test.ts` mirroring tasks 1.1 and 1.2, with `BLACKHOLE_UI_ORIGIN` for
  `Canonical`. Red first.
- [ ] 2.2 Add `BLACKHOLE_UI_ORIGIN` to `web/server/lib/config.ts` with the same validation as the portal origin
  (https, bare origin, http only for loopback outside production). Test it red first in `config.test.ts`.
- [ ] 2.3 Add a route test in `web/server/__tests__/` asserting that `GET /.well-known/security.txt` returns the file
  and not `index.html`, and that `/.well-known/unknown` still follows the SPA fallback (delta spec `admin-web-ui`).
  Red first.
- [ ] 2.4 Implement `web/server/lib/security-txt.ts`, the route file `web/server/routes/.well-known/security.txt.ts`
  and the startup wiring in `web/server/lib/startup.ts`. Make 2.1 to 2.3 green.
- [ ] 2.5 Run `npm test` and `npm run build` in `web/`. Commit `feat(web): serve an rfc 9116 security.txt` and push the
  branch. The PR is opened in 4.3.

## 3. PR 3: `build(deploy)` (branch `build/deploy-security-txt`, stacked on PR 1 and PR 2)

- [ ] 3.1 Add `deploy/charts/blackhole/tests/security-txt.sh` in the style of `exposure.sh` (it is run by `run-all.sh`
  automatically). Cases: (a) defaults without `expires` fail the render and name `securityTxt.expires` (the contact
  default is `mailto:security@onelitefeather.net`, design Decisions 2026-10-25); (b) contact and a
  valid expiry render the matching env in both the portal and the web Deployment; (c) clearing `contact` with no
  `expires` renders no `BLACKHOLE_SECURITY_CONTACT`; (d) a malformed expiry (`next year`) fails the render. Red first.
- [ ] 3.2 Add the `securityTxt` values block to `values.yaml` (`contact: mailto:security@onelitefeather.net`,
  `expires` empty, `encryption`, `acknowledgments`, `policy` empty, `preferredLanguages: en, de`) and the matching
  entries in `values.schema.json`. Add `web.origin` (renders `BLACKHOLE_UI_ORIGIN`) next to `portal.origin`.
- [ ] 3.3 Render the `BLACKHOLE_SECURITY_*` variables in `templates/portal.yaml` and `templates/web.yaml`, next to
  `BLACKHOLE_PORTAL_ORIGIN`, and add the checks to `templates/validate.yaml` (contact without expiry, malformed expiry).
  Make 3.1 green.
- [ ] 3.4 Set the fixture values in `ci/prod-values.yaml` (`mailto:security@example.invalid`, a fixed expiry string,
  and `web.origin: https://blackhole.onelitefeather.net`). Use only `.invalid` placeholders, no real contact.
- [ ] 3.5 Add a short section to `deploy/charts/blackhole/README.md`: which values to set, and the annual renewal of
  `securityTxt.expires` (design D3 and risks).
- [ ] 3.6 Run `deploy/charts/blackhole/tests/run-all.sh` (helm v4.3.0 and yq v4.53.6). Commit `build(deploy): wire the
  security.txt values into the chart` and push the branch. The PR is opened in 4.3.

## 4. Close out

- [ ] 4.1 Update `.claude/PROGRESS.md`: this change into Active while the PRs are open, then Recently completed on merge.
- [ ] 4.2 Run `openspec validate --all --strict` and fix any finding in the change artifacts.
- [ ] 4.3 Open the three PRs last, in the order of section "Ordering", each titled as its commit (`feat(portal): ...`, `feat(web): ...`, `build(deploy): ...`), with the body ending in the Claude-Session line. Do not merge. Watch the checks
  with `gh pr checks --watch` on each.
