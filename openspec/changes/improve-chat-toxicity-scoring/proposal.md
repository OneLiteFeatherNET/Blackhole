## Why

Chat toxicity is scored by a placeholder. `RuleBasedToxicityScorer` counts substring matches
against four English words. It misses German abuse, misses trivial obfuscation such as `1d10t` or
`i.d.i.o.t`, and flags harmless game talk that happens to contain a listed word. The chat ELO is
automatic (`config.yaml` principle 4), so a wrong score can mute a player without a moderator online.

The network is German-speaking and small. A separate machine-learning service would add a
deployment, a model licence and CPU cost for a community this size. Word lists with normalisation
in the backend cover the gap at no extra cost. Normalisation and the term tagger are already in
PR #427 (`elo/toxicity`), which builds on this change.

## What Changes

- **Term-list scorer behind the existing `ToxicityScorer` interface.** `TermListToxicityScorer`
  normalises the message (PR #427), matches the configured word lists with word boundaries, drops
  allow-listed game phrases, and returns the highest weight of the matched categories. The
  placeholder `RuleBasedToxicityScorer` and its `toxic-patterns` keys are removed.
- **Five categories with a weight each**: `INSULT`, `THREAT`, `SEXUAL`, `HATE` (slurs and hateful
  terms) and `SPAM`. A category's weight is its ELO strength. A matched category flags the message
  when its weight reaches `flag-threshold`.
- **Per-category ELO delta.** A flag applies `flag-delta * weight`, rounded, with reason
  `TOXICITY_FLAG`. This is today's formula with a weight per category.
- **Word lists in configuration.** `blackhole.elo.chat.toxicity.terms.<category>` and
  `blackhole.elo.chat.toxicity.allow-list`, German and English. The repository ships no word list.
  The deployment supplies its own.
- **One global mode**, `blackhole.elo.chat.toxicity.mode`: `OFF`, `SHADOW` or `ENFORCE`, default
  `OFF`. `SHADOW` counts and logs would-be flags without any ELO change. `ENFORCE` applies the flag.
- **Fail-open.** An exception in the scorer records no delta and no flag. Chat delivery and the
  request response are unaffected.

Not BREAKING. The `POST /elo/chat` response keeps its shape (`flagged`, `score`). There is no
Flyway migration, no admin panel change and no client spec bump.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `elo-scoring`: the chat toxicity requirements. The rule-based scorer requirement is removed, and
  the term-list scorer, categories, modes, shadow behaviour and fail-open rules are added.

## Prerequisite

PR #427 (`feat(elo): normalise chat text before toxicity scoring`) merges first. This change builds
on its `elo/toxicity` package and adds the `SPAM` category to its `ToxicityCategory` enum.

No amendment to `amend-data-protection-principle` is needed. This change stores no message text
(`design.md`, decision 6).

## Impact

- **Backend**: `elo/` (new scorer, category weights, mode handling in the chat toxicity service,
  metrics), `application.yml` (`blackhole.elo.chat.toxicity.*`), `.claude/rules/elo.md`
  (the scorer paragraph).
- **Database**: none.
- **Client and admin web**: none.
- **Velocity**: none. The scoring runs in-process, so the existing synchronous call stays.

## Out of scope

- A separate ML or inference service, a model, ONNX, Python or a Helm component (`design.md`,
  decision 2).
- An admin editor for word lists or modes, and a per-section mode (`design.md`, decision 5).
- Staff labels on flags, and storing message text for review (`design.md`, decisions 6 and 7).
- Automatic ELO reversal on a false positive. That belongs to the staff rollback change.
- Changing the mute check or the appeal flow.
- Rate limiting of the scoring endpoint. Infra owns rate limiting (`config.yaml` principle 5).

Ships as: feat(elo): score chat toxicity with configurable word lists
