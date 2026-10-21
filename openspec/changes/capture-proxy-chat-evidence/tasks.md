# Tasks

Prerequisite: `add-punishment-evidence` merged and the Java client regenerated. Tests follow
F.I.R.S.T.: inject `java.time.Clock` into the buffer, no sleeps, no real network (fake client).

## 1. Chat buffer

- [ ] 1.1 Add plugin config `evidence.chat-capture.*` (default disabled) with inline comments; verify a unit test that defaults leave capture off and invalid values fall back or fail clearly
- [ ] 1.2 `ChatBuffer` (per owner hash, bounded by lines and age, `Clock`-driven, clear on disconnect); verify unit tests for bound, age eviction with a fixed clock, disconnect clear and that disabled mode stores nothing
- [ ] 1.3 Chat listener feeds the buffer (public chat only, never commands, O(1) on the event thread); verify unit tests with fake events that commands are skipped

## 2. Attaching evidence

- [ ] 2.1 Snapshot builder (target lines + `context-lines` of other speakers, hashes only, IP redaction, 256-char cap); verify unit tests for ordering, context window and redaction
- [ ] 2.2 Staff punish flow: post CHAT_LOG with a fresh `correlationId`, then punish with `evidenceCorrelationId`, async with timeout, warn on failure without blocking the punishment; verify unit tests with a fake client for success, evidence failure and punish failure
- [ ] 2.3 Staff feedback text "evidence attached (n lines)" in German and English; verify the existing message-key parity test

## 3. Denial screen count

- [ ] 3.1 Async count lookup with 30 s proxy-side cache and timeout, appended to login/chat denial screens when > 0; verify unit tests for count shown, zero omitted, timeout omitted, and that no evidence content type is referenced
- [ ] 3.2 README/plugin config docs including the privacy note; verify keys match the config class

## 4. Verification

- [ ] 4.1 Manual check with `:velocity:runVelocity` against a local backend: enable capture, chat, ban, see the item in the API, rejoin and see "Beweise: 1"; record in the PR description

## 5. Pull request

- [ ] 5.1 Open the pull request titled `feat(velocity): attach chat evidence to staff punishments` against `main`; verify PR lint and `openspec validate --all --strict` pass
