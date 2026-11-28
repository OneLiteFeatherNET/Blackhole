# Tasks

Tests come first in every group (red, then green). Every test follows F.I.R.S.T.: an injected `Clock`, no sleeps,
a fake Dragonfly and a fake verifier in unit tests, a fresh fixture per test.

## 1. Dependencies and configuration

- [ ] 1.1 Add `org.altcha:altcha:2.1.1` and `org.json:json:20240303` (runtime) to the backend build, and `altcha` at the exact version 3.4.1 to the portal; check the licences in the PR description
- [ ] 1.2 Add the `blackhole.public.challenge.*` block to `application.yml` with `${ENV:default}` values and a comment for each tunable (design decision 7)

## 2. Backend: challenge core (`publicapi/challenge/`)

- [ ] 2.1 Red: unit tests with a fake verifier, a fake reserve and a fixed clock: a solution is accepted once; a replay is refused; a wrong purpose is refused; a reserve that fails gives `UNAVAILABLE`; a disabled check is skipped; every rejection gives `CHALLENGE_FAILED`
- [ ] 2.2 Green: `ChallengeVerifier` port and its adapter around `org.altcha.altcha.v2.Altcha` (`PBKDF2/SHA-256`), the purpose check, and the challenge issuer with the `data.purpose` binding
- [ ] 2.3 Red then green: `DragonflyChallengeReplayStore` with `SET ... NX PX` and its own timeout; unit tests with a fake connection cover `OK`, `null`, timeout and error, and the TTL including the 60 second margin
- [ ] 2.4 Red then green: `ChallengeKeyGuard` and the switch guard as static `check` methods, tested like `BanIdKeyGuard`: missing key refused outside local environments, short key refused, odd prefix refused, switch off refused outside local environments
- [ ] 2.5 Red then green: the counters `blackhole.public.challenge{outcome}` with a simple meter registry, one test per outcome

## 3. Backend: HTTP and sign-in and filing

- [ ] 3.1 Red: integration tests for `GET /public/challenge` (issued, unknown purpose 400, disabled `enabled: false`, `no-store`) and for `POST /public/session` and `POST /public/appeal` (no challenge 403, wrong Ban ID after a valid challenge still 401 uniform, challenge checked before eligibility, replay 403, Dragonfly stopped 503 with no session row)
- [ ] 3.2 Green: sealed `PublicChallengeDTO` (`Issued`, `Disabled`), the optional `altcha` field on `PublicSessionDTO.LoginRequest` and `PublicAppealDTO.FileRequest`, `PublicErrorKind.CHALLENGE_FAILED` (403), the new route in `PublicApi` with its OpenAPI annotations, one service call per controller method
- [ ] 3.3 Red then green: the log test. A captured appender runs a full sign-in and filing and asserts that no line contains the payload, the solution or the nonce
- [ ] 3.4 Update `.claude/rules/public-api.md` with the challenge rules, in the same style as its other bullets

## 4. Client spec

- [ ] 4.1 Copy the newest spec to `client/specs/blackhole-api-0.0.67.yml` (or the next free number, see design decision 14); add `GET /public/challenge`, the `altcha` field on the two requests, and `CHALLENGE_FAILED`; never edit a published file
- [ ] 4.2 Move the `inputSpec` pin in `client/build.gradle.kts` to the new file and run `./gradlew :client:openApiGenerate`; check that the generated client compiles

## 5. Portal

- [ ] 5.1 Red then green: `contract.ts` (route `GET /api/challenge`, optional `altcha` on the two bodies, `CHALLENGE_FAILED` in `ErrorCode`) and `bff.ts` (403 `CHALLENGE_FAILED` mapped to that code, not `FORBIDDEN`), with BFF tests for the allow-list and the body shape limit of 8 KiB
- [ ] 5.2 Red then green: `headers.ts` gets `worker-src 'self'`; the header test asserts the directive and asserts that no `blob:`, `data:` or `unsafe-eval` appears
- [ ] 5.3 Red then green: the sign-in and appeal views with a stubbed widget element. Covers: the widget is absent when the challenge answers `enabled: false`; a failed attempt resets the widget and shows a retry; the fallback message; `humanInteractionSignature` is `false`; no `test` or `mockError` attribute
- [ ] 5.4 Import `altcha/external` with the PBKDF2 and SHA workers as Vite `?worker` imports and register `PBKDF2/SHA-256`; add the widget to the sign-in and appeal forms with `type="checkbox"` and `auto="onload"`
- [ ] 5.5 German and English strings for the status, the retry, the fallback and the rejection, with the language parity test
- [ ] 5.6 Run the portal tests and the type check with `npm test` and the project's type-check script

## 6. Verification

- [ ] 6.1 Measure one solve on a real mid-range phone and on a desktop with the default cost and prefix; record the times in the PR and change the defaults only in this PR if they are far off the target
- [ ] 6.2 Targeted backend tests first, then the full suite under `flock ~/.cache/claude-heavy.lock`
- [ ] 6.3 Runtime check against the real Docker stack (`docker/docker-compose.yml`, Dragonfly included): sign in and file an appeal in a browser; check that the reserved key has a TTL, that a replayed solution is refused, and that stopping Dragonfly gives 503 with no session row; check `SET ... NX PX` on the real server
- [ ] 6.4 Browser check under the real portal CSP: no console CSP violation; keyboard-only run; a screen reader pass, recorded as a manual check in the PR; answers to open questions 1 to 3 in the PR description

## 7. Finish

- [ ] 7.1 Operator step, outside this repository: put `BLACKHOLE_PUBLIC_CHALLENGE_KEY` into the SOPS-encrypted secret of the Flux repository (`openssl rand -base64 32`), and add the edge rules for sign-in and filing to the deployment notes
- [ ] 7.2 Open the code pull request titled `feat(appeal): require a proof-of-work challenge for sign-in and appeal filing` from `origin/main`, with the body from `.github/pull_request_template.md`, no file under `openspec/`, after the proposal PR `docs(openspec): propose add-portal-bot-challenge` is merged
