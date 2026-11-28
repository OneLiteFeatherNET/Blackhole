# Proposal

**Ships as:** `feat` with the scope `appeal`. The code PR is titled `feat(appeal): require a proof-of-work
challenge for sign-in and appeal filing`. Its commits use `feat(appeal)` for the backend and `feat(portal)` for the
browser part; no other type is in this change. The proposal PR is `docs(openspec): propose add-portal-bot-challenge`.

**Decision recorded here:** the challenge is ALTCHA, self-hosted (see `design.md`, decision 1). Turnstile, hCaptcha
and reCAPTCHA were rejected, and so was an in-app rate limiter.

## Why

The appeal portal is public. A banned player signs in with a Ban ID, files an appeal and writes messages. Today
nothing stops a script from doing the same thing thousands of times:

- **Sign-in attempts.** A script tries Ban IDs at speed. Every try costs a database lookup and a log line, and the
  team's alerts for failed logins fire on noise.
- **Appeals and messages.** A script files appeals or floods an open appeal. Staff see a queue of junk, and each
  uploaded file costs scanning and storage.

The edge rate limits (operator, infrastructure) already cap requests per address. They do not make one request
more expensive, and a botnet spreads its requests over many addresses. A challenge that the browser must solve
with CPU work before the server accepts a request makes every attempt cost something, for any address.

A real player sees a short check on the sign-in and appeal forms and nothing on the messages they write
afterwards.

## What Changes

- **A proof-of-work challenge on two routes.** `POST /public/session` (sign-in) and `POST /public/appeal`
  (filing) require a solved ALTCHA challenge. The check runs in the backend before any database read.
- **Each solution is accepted once.** A solved challenge is stored as used in Dragonfly until it expires.
  A wrong Ban ID also uses up the challenge, so each guess costs one new solution.
- **Fail closed without the replay store.** If Dragonfly cannot answer, sign-in and filing answer 503. Issuing
  challenges keeps working.
- **Tunables and a dev-only switch.** Difficulty, expiry and the switch are environment variables with defaults
  in `application.yml`. The switch works only in the `dev` and `test` environments. The HMAC key is required
  outside them; a missing key stops the backend from starting.
- **Accessible widget.** The portal uses the ALTCHA widget without a visual puzzle, with keyboard and screen
  reader support, a retry, and a message when the browser cannot run the worker.
- **Metrics.** `blackhole.public.challenge{outcome}` counts issued, solved, rejected, replayed and unavailable.
- **API.** A new `GET /public/challenge` route and an `altcha` field on the two protected requests, in a new
  client spec `blackhole-api-0.0.67.yml` (the next free number at proposal time).

BREAKING: `POST /public/session` and `POST /public/appeal` answer 403 `CHALLENGE_FAILED` to a request without a
valid `altcha` field while the challenge is enabled. The only caller of these routes is the portal backend-for-frontend,
which this change updates in the same PR. The generated Java client gets one new optional field per request.
Velocity is not affected.

## Out of scope

- **A challenge on messages, file uploads and reads.** A session already required a solved challenge. Their
  cost is bounded by the open appeal, the file quota and the size limits. Open question 1 asks whether uploads
  need one too.
- **Application rate limiting.** The edge owns it (principle 5). The challenge is not a rate limiter.
- **Third-party captcha services and any cookie.** ALTCHA runs on our own server, sets no cookie and sends the
  browser to no third party.
- **Ban ID format, storage or login rules.** Unchanged; the challenge runs in front of them.
- **Sentinel** (ALTCHA's hosted service with image or audio code challenges). Not used.
- **The Flux secret itself.** The key is created and stored by an operator in the Flux repository; this repo only
  reads it from the environment.
- **Translations beyond English and German.**

## Capabilities

### New Capabilities

- `portal-bot-challenge`: the challenge route, its verification and replay rules, the failure mode, the
  tunables, the key, the widget and the CSP.

### Modified Capabilities

None. The existing `appeal-portal` and `ban-id` requirements keep their wording. The challenge check runs before
them and is specified in the new capability.

## Impact

- **Backend**: `publicapi` (challenge route, session and appeal services, error kind), a new challenge package
  (verifier, replay store, key guard), `application.yml`, metrics, dependencies `org.altcha:altcha:2.1.1` and
  `org.json:json:20240303` (runtime).
- **Client**: `client/specs/blackhole-api-0.0.67.yml` and the `inputSpec` pin in `client/build.gradle.kts`.
- **Portal**: `contract.ts`, `bff.ts`, `headers.ts` (`worker-src 'self'`), the login and appeal views, strings,
  `altcha@3.4.1`.
- **Database**: no migration. The challenge state lives in Dragonfly.
- **Operations** (not in this repo): the HMAC key in the SOPS-encrypted secret of the Flux repository, and the edge
  rules for sign-in and filing, which stay the operator's.
- **Depends on**: the appeal portal and its public API, both on `main`, and Dragonfly.
