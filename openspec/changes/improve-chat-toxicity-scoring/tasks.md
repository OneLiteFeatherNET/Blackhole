## 1. Prerequisite

- [ ] 1.1 Wait for PR #427 (`feat(elo): normalise chat text before toxicity scoring`) to merge. Rebase the implementation branch onto the updated main branch.

## 2. Red: scorer tests (F.I.R.S.T., no implementation yet)

- [ ] 2.1 Red: `TermListToxicityScorerTest` asserts that `1d10t` matches an `INSULT` term `idiot`. Fixture: a scorer built in the test from a map of terms, no config file, no clock.
- [ ] 2.2 Red: the same test class asserts that `§aidiot` and `i.d.i.o.t` both match `idiot`.
- [ ] 2.3 Red: a word-boundary test asserts that the term `ass` does not match `class`.
- [ ] 2.4 Red: a squash test asserts that a term of three letters does not match through separators, and a term of four letters does.
- [ ] 2.5 Red: an allow-list test asserts that `kill the dragon` is not tagged as `THREAT` when `kill` is a `THREAT` term and the phrase is allow-listed. The same message with another matching word still tags.
- [ ] 2.6 Red: a German test asserts that `Trottel` matches in upper case, and that an umlaut term matches both its `ä` and `ae` spellings.
- [ ] 2.7 Red: a weight test asserts that a `THREAT` match (weight 1.0) reaches the default threshold of 0.5, and that a `SPAM` match (weight 0.5 with threshold 0.6) is not flagged.
- [ ] 2.8 Red: a score test asserts that a message matching `INSULT` and `THREAT` scores the higher weight, 1.0.
- [ ] 2.9 Red: a blank message scores 0.0.

## 3. Red: service tests (mode and ELO delta)

- [ ] 3.1 Red: a `ChatToxicityService` test asserts that an `INSULT` flag (weight 0.6) in `ENFORCE` applies a CHAT delta of -30 with reason `TOXICITY_FLAG`.
- [ ] 3.2 Red: a test asserts that the same flag in `SHADOW` applies no delta and writes no evidence record. The scorer fake counts its calls, and the test asserts one call.
- [ ] 3.3 Red: a test asserts that `OFF` never calls the scorer. The scorer fake counts its calls, and the test asserts zero calls.
- [ ] 3.4 Red: a fail-open test asserts that a scorer that throws yields no delta, no flag, and a normal response.
- [ ] 3.5 Red: a log test uses a captured appender with a sentinel message text. It asserts that the sentinel never appears in any log line during a scoring call that fails or is shadowed.

## 4. Green: implementation

- [ ] 4.1 Implement `TermListToxicityScorer` in `elo/`, behind `ToxicityScorer`, on top of `ChatNormaliser` and `TermTagger` from PR #427. Add `SPAM` to `ToxicityCategory`.
- [ ] 4.2 Implement the category weights, the flag rule and the delta formula in `ChatToxicityService`. Implement the three modes (`OFF`, `SHADOW`, `ENFORCE`) from `blackhole.elo.chat.toxicity.mode`.
- [ ] 4.3 Implement the fail-open path and the log line without text.
- [ ] 4.4 Add the metrics from `design.md`, decision 8, if Micrometer is on the classpath.
- [ ] 4.5 Remove `RuleBasedToxicityScorer` and the keys `blackhole.elo.chat.toxic-patterns` and `severity-per-match`. Document `blackhole.elo.chat.toxicity.*` in `application.yml` with empty lists and an inline comment that the deployment supplies the lists (`${ENV_VAR:default}` pattern).
- [ ] 4.6 Update the scorer paragraph of `.claude/rules/elo.md`.
- [ ] 4.7 Run `./gradlew :backend:test`. All green, with no `Thread.sleep` and no system clock reads in the new tests.

## 5. Verify and ship

- [ ] 5.1 Run the backend locally against `docker/docker-compose.yml`. Send a German insult in `SHADOW` and check the log line and the unchanged ELO. Then send it in `ENFORCE` and check the delta. Record the result in the PR description.
- [ ] 5.2 Confirm that no file under `openspec/` changes in the code PR.
- [ ] 5.3 Open the code PR titled `feat(elo): score chat toxicity with configurable word lists`, from the implementation branch, after the proposal PR is merged. Fill in the PR template, and include the test list and the rollout plan from `design.md`.
