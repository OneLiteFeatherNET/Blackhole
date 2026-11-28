# Design

## Decisions

### 1. ALTCHA, self-hosted

ALTCHA is a proof-of-work challenge. The server issues a signed challenge; the browser runs a Web Worker that
searches a counter until a derived key starts with a fixed prefix; the server checks the signature and the key
with one KDF run. Nothing leaves our infrastructure, no cookie is set and no tracking runs.

Versions and licences, checked at proposal time (2026-11-02):

| Part | Version | Published | Licence | Source |
|------|---------|-----------|---------|--------|
| Widget `altcha` (npm) | 3.4.1 (latest is 3.5.0, 11 days old, too new for the two-week rule) | 2026-10-19 | MIT | `https://registry.npmjs.org/altcha/-/altcha-3.4.1.tgz`, LICENSE.txt in the package; `npm view altcha` |
| Java library `org.altcha:altcha` | 2.1.1 (latest release) | jar last-modified 2026-10-02 | MIT (POM) | `https://repo1.maven.org/maven2/org/altcha/altcha/2.1.1/altcha-2.1.1.pom`; project `https://github.com/altcha-org/altcha-lib-java` (its LICENSE path returned 404, the POM is the source) |
| `org.json:json` (runtime for the library) | 20240303 | 2024-03 | Public Domain (POM and repository LICENSE) | `https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.pom` |

The Java library implements protocol v2 (`org.altcha.altcha.v2`). It supports `PBKDF2/SHA-256`, verifies with
`Altcha.verifySolution`, and returns `verified`, `expired`, `invalidSignature`, `invalidSolution`. Its README
says `org.json` must be present at runtime.

Rejected:

- **Cloudflare Turnstile, hCaptcha, reCAPTCHA.** Each sends the player's IP address and browser data to a third
  party. That needs a processing agreement, a consent discussion for players under GDPR, and an external script in
  the CSP. Principle 1 (GDPR by design) rules it out.
- **An in-app rate limiter.** Principle 5 says infrastructure owns rate limiting. A limiter in the app would also
  count per address only, which a botnet defeats. See decision 12.
- **ALTCHA Sentinel** (the hosted service). It sends data to a third party and is needed only for code challenges,
  which this change does not use.

### 2. Where the challenge is required

| Route | Challenge | Why |
|-------|-----------|-----|
| `POST /public/session` (sign-in) | required | The only anonymous write. Each guess of a Ban ID costs one solved challenge. |
| `POST /public/appeal` (filing) | required | Creates a case record and staff work. |
| `GET /public/challenge` | not needed | Issuing is cheap; the cost is on solving. |
| `POST /public/messages`, `POST /public/attachments` | not required | A session already needs a solved challenge. Messages go to one open appeal and are bounded by the open-appeal rule, PII checks and the 2000 character limit. Uploads are bounded by the per-appeal file quota and the size limits. Open question 1. |
| Reads (`GET /public/...`) | not required | Each needs a session. |

A real player solves the challenge twice at most: once to sign in and once to file. The messages the player
writes afterwards need no check.

### 3. Where verification happens

The backend verifies, not the portal server. The backend is the authority for sessions and appeals; the portal
BFF only forwards the `altcha` field and checks its shape (a string of at most 8 KiB). A BFF bypass therefore
cannot skip the check.

Order inside `PlayerSessionService.login` and `PublicAppealService.file`: **challenge first**, then the Ban ID
lookup or the eligibility checks. A refused challenge never reads the database and never records an attempt.
The challenge rejection uses one code, `CHALLENGE_FAILED`, for missing, malformed, badly signed, expired,
replayed and wrong-purpose challenges, so the answer tells an attacker nothing about which part failed.
Ban ID errors keep their uniform 401 `INVALID_BAN_ID` body.

Verification steps, in order:

1. Parse the base64 JSON payload (`challenge`, `solution`). A parse error is a rejection.
2. Run `Altcha.verifySolution` with the HMAC key and `PBKDF2/SHA-256`. This checks the expiry, the signature
   (HMAC over the canonical JSON of the parameters) and the key prefix. The signature check comes before the
   KDF run, so a forged payload costs an attacker a KDF run only if it carries a valid signature, which only the
   server issues.
3. Check `challenge.parameters.data.purpose` equals the purpose of the route (`sign-in` or `appeal`). `data` is
   inside the signed parameters, so the client cannot change it.
4. Reserve the nonce in Dragonfly (decision 4). A nonce already reserved is a replay.

### 4. Replay protection

- **Store**: Dragonfly, the Redis-protocol server the backend already uses. The key is
  `blackhole:challenge:used:<nonce>`; the value is `1`; the TTL is the challenge's remaining lifetime plus a 60 second
  margin for clock skew between replicas. The nonce is 16 random bytes per challenge; it identifies no player.
- **Atomic reserve**: `SET key 1 NX PX <ttl>` (the Lettuce `set` with `SetArgs.nx().px()`). Two concurrent requests
  with the same solution: exactly one gets `OK`, the other `null` (replayed). Dragonfly implements the Redis `SET`
  options; the runtime check (task 6.3) confirms it on the real server.
- **Not `SharedCache`**. `SharedCache` is fail-open by design (a read error is a miss, a write error is logged).
  For a replay store a failed write means the solution can be used again, so the replay store is a separate class
  with its own timeout.
- **Consumed on any verified attempt.** The nonce is reserved as soon as the challenge verifies, even when the Ban ID
  or the eligibility check then fails. Otherwise one solved challenge could be tried against many Ban IDs inside its
  lifetime, which defeats the purpose. The cost for the player: a wrong Ban ID or a refused appeal needs a new
  solve. The portal starts a new solve after every failed attempt.
- **Reserve only after a verified solution.** A garbage payload never writes to Dragonfly.

### 5. When Dragonfly is down

**Sign-in and filing fail closed**: answer 503 `UNAVAILABLE`, create no session and no appeal. Issuing challenges
still works, because it is stateless (HMAC and random bytes).

Why: without the reserve, a solved challenge can be replayed for its whole lifetime. The challenge would then cost
an attacker one solve per five minutes instead of one per attempt, which is the protection this change exists for.
Failing open would stop the protection exactly when an attacker can cause or wait out a Dragonfly outage.

Cost: a Dragonfly outage stops sign-in and filing for players. The existing public routes do not depend on
Dragonfly today, so this is a new dependency of two routes. There is no switch to fail open in this change.
Open question 2 asks whether the maintainers want one.

The store call has its own timeout, `blackhole.public.challenge.store-timeout` (default `PT0.5S`), and any error or
timeout counts as unavailable.

### 6. The challenge format

- **Algorithm**: `PBKDF2/SHA-256`. The library and the widget both support it, and the widget has it bundled.
- **Cost**: PBKDF2 iterations, from `BLACKHOLE_PUBLIC_CHALLENGE_COST`. Default 5000, the value in the library's
  README example. Provisional: measure on a mid-range phone before the default is final.
- **Key prefix**: a static prefix in hex, from `BLACKHOLE_PUBLIC_CHALLENGE_KEY_PREFIX`. Default `00` (one byte, so
  about 256 tries on average). Must be an even number of hex characters. The library's default is `00` as well.
  Difficulty is the cost times the expected tries; tune both, measure both.
- **Expiry**: `BLACKHOLE_PUBLIC_CHALLENGE_EXPIRY`, default `PT5M`. A player who solves and then types the Ban ID
  slowly gets an expired challenge and a new solve.
- **Purpose**: `data = {"purpose": "sign-in" | "appeal"}`. Issued by `GET /public/challenge?purpose=`; unknown
  purposes answer 400.
- **Clock**: the library reads the system clock for `expiresInSeconds` and in `verifySolution`; it has no clock
  parameter. The verifier is therefore a port (`ChallengeVerifier`) with an adapter around the library, so the unit
  tests use a fake and never depend on system time. The expiry logic that matters to the design (the replay TTL)
  uses the injected `Clock`.

### 7. Difficulty and expiry as tunables

All values are environment variables with inline defaults, documented by a comment in `application.yml`:

| Variable | Default | Meaning |
|----------|---------|---------|
| `BLACKHOLE_PUBLIC_CHALLENGE_ENABLED` | `true` | Dev and test only; see decision 9. |
| `BLACKHOLE_PUBLIC_CHALLENGE_KEY` | none | HMAC key, required outside dev and test; decision 8. |
| `BLACKHOLE_PUBLIC_CHALLENGE_COST` | `5000` | PBKDF2 iterations. |
| `BLACKHOLE_PUBLIC_CHALLENGE_KEY_PREFIX` | `00` | Hex prefix the solution must produce. |
| `BLACKHOLE_PUBLIC_CHALLENGE_EXPIRY` | `PT5M` | Lifetime of a challenge and the replay TTL base. |
| `BLACKHOLE_PUBLIC_CHALLENGE_STORE_TIMEOUT` | `PT0.5S` | Dragonfly call timeout for the reserve. |

Target, to be measured in task 6.1: a few seconds on a mid-range phone for one solve, and a cost to an attacker
that a single server cannot pay for at flood rates. The numbers are not final until measured.

### 8. The HMAC key

- **Name**: `BLACKHOLE_PUBLIC_CHALLENGE_KEY`, a base64 string of at least 32 random bytes
  (`openssl rand -base64 32`).
- **Guard**: a `ChallengeKeyGuard`, like `BanIdKeyGuard`. Outside `dev` and `test` an unset or short key stops the
  backend from starting. The error message never names the key.
- **Secret handling**: the key lives only in the environment. In the cluster it comes from a SOPS-encrypted secret
  in the Flux repository (operator step, task 7.1). It is never in this repo, never in `application.yml` and never
  logged.
- **Rotation**: changing the key invalidates the challenges still in flight (at most one expiry window). No data is
  stored under the key, so there is no reissue job, unlike the Ban ID key.

### 9. The dev-only switch

`BLACKHOLE_PUBLIC_CHALLENGE_ENABLED=false` skips the check and makes `GET /public/challenge` answer
`{"enabled": false}`. The guard refuses to start with the switch off outside `dev` and `test`, so it cannot be
turned off in a deployment by mistake. The portal renders the widget only when the challenge answers
`enabled: true`; it sends no `altcha` field otherwise.

### 10. The widget and the CSP

What the widget does, read from the npm package 3.4.1 (`README.md` and `dist/`):

- The default bundle creates its Web Workers from `blob:` and `data:` URLs (`createObjectURL`, `new Worker("data:…")`).
  The portal CSP (`portal/server/lib/headers.ts`) has `script-src 'self'` and no `worker-src`, so workers fall back
  to `script-src` and those URLs are blocked.
- The README's CSP section says the strict build is `altcha/external`, which ships no bundled workers. The
  portal imports it, registers the PBKDF2 and SHA workers as Vite `?worker` imports (same-origin chunks) and
  calls `$altcha.algorithms.set('PBKDF2/SHA-256', …)`. Workers then load from `'self'`.
- **CSP change**: add `worker-src 'self'` explicitly. No `blob:`, no `data:`, no `unsafe-eval`. `script-src` and
  `style-src` stay as they are. The CSP test in `headers.test.ts` gets the new directive.
- **To verify, not assumed**: that the `altcha/external` build with `altcha.css` raises no CSP violation in a browser
  under the real CSP (task 6.4). If its styles are injected at run time, the style rule needs a decision then.
- **Off by default**:
  - `humanInteractionSignature`: the README says it is on by default. The collector listens to `pointermove`,
    `scroll`, `touchmove`, `keydown`, `pointerdown` and `focusin` and adds the result to the payload. That is
    behavioural data about the player, which principle 1 does not need. The portal sets it to `false`.
  - `test`, `mockError`: never set outside tests.
  - `setCookie`: never set (no cookie).
  - The code-challenge modal (image or audio) needs Sentinel or a custom server that returns code fields. The
    challenge we issue has none, so the widget cannot show one.

### 11. Accessibility and the fallback

- **No visual puzzle.** The widget shows a labelled checkbox-style control (`type="checkbox"`) and a status. The
  solve runs when the form appears (`auto="onload"`), so a keyboard or screen reader player need only submit.
- **Keyboard**: the control is in the tab order before the submit button. A failed solve shows a retry button that
  works with Enter and Space.
- **Screen reader**: the status is in a polite live region. The widget's English and German strings come from
  `altcha/i18n` for the two languages the portal ships. Labels and status wording are checked with a screen reader
  before merge (manual check, recorded in the PR; a test cannot prove it).
- **Fallback**: when the worker cannot start (blocked worker, no Web Worker support, or a timeout), the portal shows
  a message that says the check could not run in this browser, asks the player to use a current browser with
  JavaScript enabled, and keeps the Ban ID or statement the player typed. The message is a string in `en.json` and
  `de.json`, with the parity test already in the portal.

### 12. How this complements the infrastructure limits

The edge keeps owning rate limiting (principle 5): per-address limits on `POST /public/session` and on the write
routes stay exactly as the deployment notes describe. The challenge answers a different question. It makes each
request cost CPU on the client, which an address limit cannot do for a botnet that spreads requests over many
addresses, and it stops a script that replays the same request. It is not a limiter: it counts nothing per address
and stores nothing per player.

### 13. Privacy and logs

- **Stored**: only the nonce of each accepted solution, with a TTL, in Dragonfly. No IP address, no Ban ID, no
  punishment or session identifier. Retention is the TTL, at most the expiry plus 60 seconds. This is documented
  here so the retention rule of principle 1 has an entry; no sweeper is needed because Dragonfly expires the keys.
- **Not read**: the client address. The challenge route does not read `X-Forwarded-For`, which the existing rule
  for `/public` already states.
- **Not logged**: the challenge, the solution and the payload. A test captures the log output of the whole flow and
  asserts that neither the payload nor the nonce appears. The Ban ID rule (never logged) stays as it is.
- **Metrics** carry only the `outcome` tag.

### 14. The API

- **Spec**: a new `client/specs/blackhole-api-0.0.67.yml`, the next file after `0.0.66` at proposal time. The number is
  taken at implementation time (newest + 1); no published file is edited. The `inputSpec` pin in
  `client/build.gradle.kts` moves to it.
- **Routes**:
  - `GET /public/challenge?purpose=sign-in|appeal` answers `PublicChallengeDTO.Issued` (the challenge JSON as the
    widget takes it) or `PublicChallengeDTO.Disabled` (`enabled: false`). `no-store`. Anonymous.
  - `POST /public/session` takes `altcha` in `PublicSessionDTO.LoginRequest`, an optional string.
  - `POST /public/appeal` takes `altcha` in `PublicAppealDTO.FileRequest`, an optional string.
- **Shape**: sealed marker interface per resource, as the DTO contract requires. A new `PublicErrorKind.CHALLENGE_FAILED`
  (403) carries the code `CHALLENGE_FAILED`.
- **Layering**: `PublicController` gains one method and passes the `altcha` value to the service. The verifier, the
  replay store and the key guard live in a new package under `publicapi/challenge/`; the controller does not touch
  them.
- **Portal**: `contract.ts` gets the route, the optional body field and `CHALLENGE_FAILED` in `ErrorCode`;
  `bff.ts` maps a 403 `CHALLENGE_FAILED` to that code instead of `FORBIDDEN`.

### 15. Metrics

`blackhole.public.challenge{outcome}` with `outcome` one of:

- `issued`: a challenge was returned.
- `solved`: a verified solution whose nonce was reserved.
- `rejected`: parse error, bad signature, expiry, bad key prefix, wrong purpose, or missing field.
- `replayed`: a verified solution whose nonce was already reserved.
- `unavailable`: the reserve could not be answered; the request got 503.

`solved` counts verified solutions that were accepted at the reserve; the sign-in or filing outcome is counted by
the existing `blackhole.public.login` meter, and nothing about the outcome of the Ban ID appears in these counters.

### 16. Flyway and the database

No migration. The challenge state is in Dragonfly, and no table changes. Nothing in the database is read or written
for a challenge.

## Risks

- **Dragonfly outage stops sign-in and filing** (decision 5). Visible in the 503 rate and the `unavailable` outcome;
  the portal shows the existing unavailable message.
- **A slow phone fails the solve** and sees a retry. The cost default is measured before it is final (task 6.1).
- **The widget changes its CSP needs in a later version.** The version is pinned exactly; the CSP browser check runs
  in the PR; an upgrade is a separate change.
- **A player who mistypes the Ban ID must solve again.** Accepted in decision 4; the portal makes the retry one click.
- **Users who block workers or use a very old browser cannot sign in.** The fallback message says so (decision 11).
  This is a conscious exclusion, to be mentioned in the operator notes.

## Open questions

1. **Uploads and messages.** Should a file upload need its own challenge? It costs storage and scanning, and the
   quota is per appeal. Proposed answer: no in this change; add it if the metrics or the quota show abuse of one
   session.
2. **Fail open during a Dragonfly outage?** Proposed answer: no (decision 5). If the maintainers want a switch, it is
   a separate change with a clear operator decision.
3. **Who runs the cost measurement?** Proposed: the implementer, on a real mid-range phone, recorded in the PR.
4. **Clock.** The library reads system time. Replicas with clock skew could accept a solution at the edge of its
   expiry; the 60 second TTL margin covers the reserve, not the expiry check. Accepted; NTP is the operator's.
