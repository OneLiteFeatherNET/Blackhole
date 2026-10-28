## Context

`POST /elo/chat` (`elo/controller/EloController`, `EloApi`) receives a SHA-512 owner hash and a
message of at most 512 characters from the Velocity plugin (`PlayerChatListener.onChat`). The
backend hands the text to `ChatToxicityService.evaluate`, which asks a `ToxicityScorer`. A score at
or above `blackhole.elo.chat.flag-threshold` applies a CHAT delta of `flag-delta * score` with
reason `TOXICITY_FLAG` and stores a hash-only `CHAT_MESSAGE` evidence record.

The only implementation is `RuleBasedToxicityScorer` (`elo/RuleBasedToxicityScorer.java`), a
substring counter over `blackhole.elo.chat.toxic-patterns`. Its own comment calls it a placeholder.

PR #427 adds the pure functions this change needs in `elo/toxicity`: `ChatNormaliser` (colour codes,
invisible characters, confusables, leet, case folding, German `ß` and umlauts, repeated letters),
`TermTagger` (word-boundary matching on the plain form, squashed matching for terms of four or more
letters, allow-list removal) and `ToxicityCategory` (`INSULT`, `THREAT`, `SEXUAL`, `HATE`).

Principles that apply (`openspec/config.yaml`): GDPR-by-design (principle 1), thin controllers and a
service per feature package (principle 3), dual-ELO stays automatic and contestable (principle 4),
infra owns rate limiting (principle 5).

## Goals / Non-goals

Goals:

- Catch German and English abuse, including obfuscated spellings, without a new deployment.
- Per-category ELO strength, so a threat hits harder than a mild insult.
- A safe rollout: `OFF`, then `SHADOW`, then `ENFORCE`, with no ELO change in `SHADOW`.
- No message text stored.

Non-goals: a machine-learning model, an inference service, an admin editor for lists, staff
labelling, per-section settings, and any change to the Velocity plugin.

## Decisions

### 1. Scoring engine: a term-list scorer in the backend

`TermListToxicityScorer` implements the existing `ToxicityScorer` seam and runs in-process. For
one message it does the following.

1. Normalise with `ChatNormaliser` (PR #427).
2. Remove allow-listed phrases with `TermTagger` (PR #427).
3. Collect the categories whose terms match.
4. Return the highest weight among those categories, or `0.0` when none matched.

A blank message scores `0.0`. The scorer has no shared mutable state and no I/O, so the whole
scoring path runs in microseconds on the chat path. The `ToxicityScorer` seam stays so that a
different scorer can be provided later without touching `ChatToxicityService`.

### 2. No ML service

Rejected: a self-hosted ONNX Runtime model service (XLM-RoBERTa toxicity classifier) in its own
container. Its costs are an extra deployment with its own Helm values, a pinned image, a model of
about 0.6 GiB in int8, CPU for 2 replicas at peak, and a model licence to review (the best German
candidate is openrail++). For a community of this size those costs are not worth it. Other rejected
options: running the model in the backend JVM (it inflates the backend pods and couples releases)
and SaaS moderation APIs (every chat message leaves the network).

Revisit only if the false-positive or false-negative rate, measured in `SHADOW` over one month, is
not acceptable to the maintainer. A revisit is its own change with its own proposal.

### 3. Normalisation: the tagger view only

A classifier needed a light "model view", but there is no classifier. The scorer uses PR #427's
tagger view only. The normalised text is never stored and never logged.

### 4. Categories, weights and the flag rule

| Category | Meaning | Default weight (proposal) |
|---|---|---|
| `THREAT` | threats against players or staff | 1.0 |
| `HATE` | slurs and hateful terms | 0.9 |
| `SEXUAL` | sexual content directed at a player | 0.8 |
| `INSULT` | insults | 0.6 |
| `SPAM` | repeated advertising or link spam | 0.5 |

- A category matches when any of its terms matches the tagger view.
- The message score is the highest weight among the matched categories, or `0.0`.
- A message is flagged when the score is at least `flag-threshold` (proposal: `0.5`). Set a weight
  below the threshold to report a category without flagging it.
- The ELO delta is `round(flag-delta * score)`, with `flag-delta` at `-50` by default. An `INSULT`
  match therefore applies `-30`, and a `THREAT` match applies `-50`.

The weights are defaults for the maintainer to confirm. Operators override them in config.

### 5. Word lists in configuration, not a table and not a panel editor

Rejected: a `chat_toxicity_term` table with an admin editor in the panel. The table needs a Flyway
migration, an audit trail, a panel screen and a client spec bump, and it still has to be seeded.
The lists change rarely, and a change through configuration plus a restart is cheap. Configuration
keeps the list content out of the repository.

- `blackhole.elo.chat.toxicity.terms.<category>`: lists of words and phrases, German and English.
- `blackhole.elo.chat.toxicity.allow-list`: game phrases such as `kill the dragon`, `bed wars`.
- `blackhole.elo.chat.toxicity.weights.<category>`: weights as in decision 4.

The repository ships the keys with empty lists and a comment. The deployment fills them in.
Revisit if staff change lists more than once a week.

### 6. One global mode, and no stored message text

Modes: `blackhole.elo.chat.toxicity.mode`, one of:

- `OFF`: the scorer is not called.
- `SHADOW`: the scorer is called. A would-be flag increments a metric and writes one log line with
  the category, the weight and the would-be delta. The log line has no message text and no owner
  hash. No ELO change and no evidence record.
- `ENFORCE`: a flag applies the delta and writes the hash-only evidence record, as today.

The default is `OFF`. Changing the mode is a configuration change and a restart. The previous design
had per-section modes stored in a table, and a labelled-sample store with message text. Both are
removed. Per-section settings have no use while there is one chat scope.

Staff review of would-be flags in `SHADOW` runs through the metrics and the log. A store of flagged
message text would be personal data and would need the amendment in
`amend-data-protection-principle` first. It is an open question, not part of this change.

### 7. Fail-open

If the scorer throws, `ChatToxicityService` records no delta and no flag for that message, logs the
outcome without the message text, and still answers the request. Chat delivery is decided by the
mute check before the signal is sent (`PlayerChatListener`), so this change cannot delay or cancel
delivery.

No timeout, batcher or circuit breaker is needed, since the scorer is in-process. Patterns are built
from quoted literal terms, so matching has no catastrophic backtracking.

### 8. Metrics

If Micrometer is already on the backend classpath (checked at implementation time):

- `blackhole.chat.toxicity.requests{outcome,mode}`: `scored`, `error`, `off`.
- `blackhole.chat.toxicity.flags{category,mode}`: flags per category.

No tag carries an owner hash, a player name or message text.

### 9. Velocity is unchanged

The scoring runs in-process, so the existing synchronous call from `PlayerChatListener` stays as it
is. Moving it off the event thread is a separate question, and this change does not need it.

## Risks / Trade-offs

- **German slang and false positives.** Word lists produce false positives on in-game banter and on
  words that have a harmless sense. Mitigation: `SHADOW` first, the allow-list, weights below the
  threshold for weak categories.
- **False negatives.** Lists miss new spellings and slang. Mitigation: operators extend the lists
  from the `SHADOW` log. Residual risk: the lists are only as good as their maintainer.
- **Spam and inflation of the list.** Lists grow over time. Mitigation: they live in config and are
  reviewed like any other config change.
- **Normalisation edge cases.** The normalisation rules are tested in PR #427. This change adds tests
  for the scorer only.

## Migration plan

1. Merge PR #427.
2. Deploy the backend with `mode: OFF`, and the lists filled in by the deployment.
3. Set `SHADOW`. Run for at least 14 days, and review the metrics and the log.
4. Adjust the lists, the allow-list and the weights from what the log shows.
5. Set `ENFORCE`. Rollback is `OFF` plus a restart, with no code change.

Existing `TOXICITY_FLAG` history rows and hash-only `CHAT_MESSAGE` evidence stay as they are. There
is no Flyway migration and no client spec bump. The change is not BREAKING.

## Open questions

1. Are the default weights and the `flag-threshold` of `0.5` right? The maintainer confirms them.
2. Does a `SHADOW` review need a stored list of would-be flags? If yes, that needs the amendment in
   `amend-data-protection-principle` and a separate change.
3. Should a mode change be possible without a restart? Today it is config plus restart. A runtime
   switch would need a store, so it is not part of this change.
4. Should a `FALSE_POSITIVE` decision reverse the `TOXICITY_FLAG` delta? Proposed: no, not in this
   change. Reversal belongs to the staff rollback change.
