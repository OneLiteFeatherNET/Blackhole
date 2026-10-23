# Tasks

Tests follow F.I.R.S.T.: render components to plain text and walk the tree for click/hover
events, inject a fixed `java.time.Clock` and a `ZoneId`, no running proxy, no network, no sleeps.
Each numbered group below is one pull request in a stack (each based on the previous one); every
title is a Conventional Commit `feat(velocity): ...`. Run `./gradlew :velocity:test` and
`openspec validate --all --strict` before each PR.

## 1. Style foundation (PR 1: `feat(velocity): add a central style for staff messages`)

- [x] 1.1 `BlackholeTheme` tag resolver (`bh:prefix|ok|err|warn|success|error|warning|info|player|section|duration|id|key|up|down|muted|sep`) registered on the `MiniMessageTranslationStore`; verify unit tests that each tag yields the intended colour/decoration/glyph and that only allowed glyphs (`✔ ✖ ⚠ » · → ▸ −`) occur
- [x] 1.2 `Mode` (RICH/PLAIN), `Fmt` argument builders (player, short id, section, score delta with sign and direction, absolute time in a given `ZoneId`), and `Permissions` view for button decisions; verify unit tests for events present in RICH and absent in PLAIN, and for time rendering with a fixed zone
- [x] 1.3 Bundle test additions: tags in the texts are known `bh:` tags, no unused keys, placeholders still equal in en/de; verify `I18nBundlesTest` stays green and fails on a deliberately unknown tag in a scratch test
- [x] 1.4 Render every existing key through the new store; verify a test that `punishment.screen.*` output is unchanged

## 2. Punish result, errors and usage (PR 2: `feat(velocity): restyle ban, mute and punish feedback`)

- [x] 2.1 `StaffMessages.applied` block (header, step/duration, score delta, id, escalation warning line) replacing `PunishmentMessages.applied`, with hover/click per design D5 and the undo button gated by permission; verify unit tests for temporary, escalation, PLAIN inlining, button hidden without permission, German text
- [x] 2.2 Unknown-template suggestions (`closest`), wrong-kind hint naming the fitting command, conflict/backend-failure lines, evidence lines restyled; verify unit tests for ranking, limit of five, no-match text and that no status code is shown
- [x] 2.3 Cloud exception handlers for invalid syntax and argument errors (usage line, target-not-found text); verify unit tests on the formatter with a fake exception, and the `TargetParser` message
- [x] 2.4 New en/de keys, old `punishment.result.*` removed; verify key-parity test

## 3. Unban and unmute (PR 3: `feat(velocity): restyle unban and unmute feedback`)

- [ ] 3.1 Success, nothing-active, unknown/wrong section, permanent-needs-permission and failure messages with the shared style and next-step hints (section list, `info` command); verify unit tests per `RevokeOutcome` in RICH and PLAIN
- [ ] 3.2 Player-name click to `/elo` and German/English texts; verify click event tests and key parity

## 4. ELO, info and history blocks (PR 4: `feat(velocity): restyle elo, info and history output`)

- [ ] 4.1 `/elo` block with score colours by threshold, section hover with thresholds, permanent marker; verify unit tests for the three colour states and hover content
- [ ] 4.2 `info` and `history` blocks moved into the bundles (no hard-coded English, no possessive header), short-id copy, entry hover, lifted/permanent/temporary tags; verify unit tests including empty profile and empty history
- [ ] 4.3 Widen `/elo`, `info`, `history` to `CommandSource` so console gets the PLAIN rendering; verify unit tests that a non-player receiver yields no events and that the other commands remain player-only

## 5. Verification

- [ ] 5.1 Manual check with `:velocity:runVelocity` against a local backend: run each command as player (hover, click copy, `/elo` suggestion, undo suggestion not executed, button hidden without permission), as console (`/elo`), in English and German clients, and with a wrong syntax and an unknown template; record what was seen in the last PR description

## 6. Pull requests

- [ ] 6.1 Open the four stacked pull requests against `main` (each based on the previous branch, retargeted to `main` as its base merges) titled `feat(velocity): add a central style for staff messages`, `feat(velocity): restyle ban, mute and punish feedback`, `feat(velocity): restyle unban and unmute feedback` and `feat(velocity): restyle elo, info and history output`; verify PR lint, CI and `openspec validate --all --strict` pass on each
