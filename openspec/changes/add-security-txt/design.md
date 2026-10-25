# Design

## Context

The portal (`portal/`) and the panel (`web/`) are both Nitro `node-server` apps with a
catch-all route. The portal's `server/routes/[...].ts` is an SPA fallback, and its
`server/api/[...path].ts` is the BFF that forwards only the allow-listed `/api` routes. The
panel's `server/routes/[...].ts` is the same kind of fallback. Both apps read their settings once
at startup through `lib/config.ts` (portal) and `lib/config.ts` (web), validate them, and exit
non-zero on invalid input (`lib/startup.ts`). Security headers are applied in a `request` hook
(`plugins/security-headers.ts`), so they reach every response, including the fallback.

Helm (`deploy/charts/blackhole`) exposes each app through an Ingress on a Cloudflare tunnel
(`portal.ingress.host: appeal.onelitefeather.net`, `web.ingress.host: blackhole.onelitefeather.net`).
`BLACKHOLE_PORTAL_ORIGIN` is already set from `portal.origin` in `templates/portal.yaml`. The
panel has no origin setting yet. The chart tests live in `deploy/charts/blackhole/tests/*.sh`
and `run-all.sh` runs every script there.

See `proposal.md` for the motivation and the scope.

## Goals / Non-Goals

**Goals:**
- One behaviour, the same on both hosts, configured by the same variable names.
- A stale `Expires` or a malformed value cannot ship unnoticed.
- The route works with no login and no backend, and stays outside the BFF allow-list.

**Non-Goals:**
- Signing the file, a disclosure policy text, a `security.txt` at the top level.
- Any change to the backend, the proxy, the client or the database.

## Decisions

### D1. Runtime configuration, not a static file
The content comes from `BLACKHOLE_SECURITY_*` environment variables, which the Helm chart sets
from a `securityTxt` values block. A static file in `public/` would bake `Expires` into the image
and the image would go stale without anyone noticing. Alternatives considered: a static file
per host (rejected for the reason above), and a file in a ConfigMap mounted into the pod (rejected:
it is one more moving part and the apps already have a validated env pattern).

### D2. `Expires` is set by the operator, not computed
An operator sets `securityTxt.expires` to a date they have looked at. A computed value (now plus
one year, evaluated at each start) would move forward on every restart, so the file would never
become stale. That defeats the field. Alternative considered: the chart computes a default when
the value is empty. Rejected for the same reason; the empty value is an error when `contact` is set.

### D3. Validation in two places
- **Helm render** (`validate.yaml`): fails when `contact` is set without `expires`, or `expires` is
  not an RFC 3339 timestamp. The chart does not compare against the date: a render-time window check
  would make the chart test depend on the system clock and start failing a year after it was written.
- **App startup** (`lib/config.ts`, one parser per app, pure function, clock injected as a
  parameter so tests do not read the system time): refuses to start on a malformed value, a past
  `Expires`, or one more than one calendar year ahead. This is the same fail-fast pattern as the
  other settings. It is the only guard against an expired value.

### D4. Route placement
Each app adds one explicit route for `/.well-known/security.txt`. In Nitro's file-based routing,
a static path beats the `[...]` catch-all, so the route wins over the SPA fallback. The portal's
`/api/**` BFF is not touched: the route is outside `/api`, so the allow-list in
`lib/bff.ts` does not see it and does not need an entry. The implementation first confirms, with
a failing test, that a directory name starting with a dot is routed by Nitro; if it is not, the
route is registered by a plugin with an exact path match instead. Either way the route must
not be listed under `/api`.

### D5. Canonical from the app's own origin
`Canonical` is `<origin>/.well-known/security.txt`. The portal reads `BLACKHOLE_PORTAL_ORIGIN`, which
already exists. The panel gets `BLACKHOLE_UI_ORIGIN`, validated like the portal's origin
(https, bare origin, http only for loopback outside production), and set from a new
`web.origin` value. Alternative: derive it from the request `Host` header. Rejected: a host header
is client-controlled and would let a request change the content.

### D6. Unsigned in this version
RFC 9116 recommends an OpenPGP cleartext signature but does not require it. Signing needs a
private key in a Secret and a signing step at build or start time. That is a separate decision
about key custody, so v1 ships unsigned and the file says nothing about a signature.

### D7. Headers and caching
`Content-Type: text/plain; charset=utf-8` (RFC 9116 requires `text/plain` with the
`utf-8` charset). `Cache-Control: public, max-age=86400`. The one-day window is short enough that a
changed contact reaches scanners within a day, and long enough to keep the route cheap. The
existing security headers are kept (the `request` hook already applies them).

### D8. Unset contact disables the route
With no `BLACKHOLE_SECURITY_CONTACT` the route answers 404 and the app logs one warning. Making it a
startup error would break every deployment that has not set the value yet, and the file is not part
of the app's function. Chart defaults leave the contact empty, so no personal or placeholder contact
reaches a deployment by default.

### D9. Shared shape, not shared code
Both apps get the same parser, written in each app's `lib/` (the two apps do not share a package
and the parser is about 80 lines). A shared workspace package would couple two release cycles for
this one feature. Alternative: a shared TypeScript workspace package. Rejected for the same reason.

## Risks / Trade-offs

- **[A running pod keeps serving an `Expires` that has passed]** → the file is only re-validated at
  start. Mitigation: the runbook asks for an annual renewal of `securityTxt.expires` (in
  `deploy/charts/blackhole/README.md`), and the next deploy fails at startup if it has lapsed.
- **[Startup refusal on a past `Expires` crash-loops new pods]** → with a rolling update the old
  ReplicaSet keeps serving while the new pods fail, so the public hosts stay up and the stalled
  rollout is visible. Trade-off accepted: a loud failure is the purpose of the field.
- **[Two copies of the parser can drift]** → each copy is covered by the same test cases
  (`Expires` in the past, more than a year ahead, malformed contact, unset contact).
- **[Config principle conflict]** → `openspec/config.yaml` principle 2 says every route except
  `GET /health` needs a verified token. The change adds the exemption
  `GET /.well-known/security.txt` to that principle as part of its apply work, and says so in the PR.
- **[Clock]** → tests inject a fixed `Clock`/`now` value; no test reads the system time.

## Migration Plan

- No database, no Flyway migration, no existing rows to migrate.
- Order: `feat(portal)` and `feat(web)` merge first (inert without the variables), then
  `build(deploy)` sets the values in `ci/prod-values.yaml` and the chart.
- Rollback: unset `securityTxt.contact` and roll the release. The route answers 404 again and
  nothing else changes.
