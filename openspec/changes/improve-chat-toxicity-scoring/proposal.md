## Why

Chat toxicity is scored by a placeholder. `RuleBasedToxicityScorer` counts substring matches
against a short English keyword list (`stupid,idiot,shut up,hate you`). It misses almost all German
abuse, misses trivial obfuscation such as `1d10t` or `i.d.i.o.t`, and flags harmless game talk that
happens to contain a listed word. Staff see the result as either silence (abuse passes) or noise
(false flags that lower a player's chat ELO and can trigger an automatic mute).

The network is German-speaking, so the scorer must handle German and English at least. The
chat ELO is automatic (`config.yaml` principle 4): a wrong score can mute a player without a
moderator online. So the replacement has to be measured in shadow mode first, must never block
chat, and must give staff a way to mark false positives that feeds later tuning.

## What Changes

- **Real multilingual scorer behind the existing `ToxicityScorer` interface.** The default
  becomes a self-hosted inference service (CPU, ONNX Runtime, XLM-RoBERTa-based toxicity
  classifier) that the backend calls over HTTP at a fixed configured URL. The rule-based scorer
  is removed as the default path. The in-JVM and SaaS alternatives are compared in `design.md`.
- **Category scores** for `INSULT`, `THREAT`, `SEXUAL` and `HATE` next to the overall score.
  `POST /elo/chat` returns them. Phase 1 derives categories from the model's toxic verdict plus a
  German/English term tagger (see `design.md`, decision 3).
- **Per-category thresholds per section**, editable in the admin panel (ADMIN). Defaults come from
  `blackhole.elo.chat.*`. A section can have its own thresholds and its own mode.
- **Minecraft slang and obfuscation normalisation** before scoring: legacy colour codes (`§a`),
  invisible characters, confusable letters, leet speak (`1d10t`), spacing and punctuation inside a
  word (`i.d.i.o.t`), repeated letters, and a per-section allow-list of game phrases (`kill the
  dragon`).
- **Shadow mode** per section: the score is computed, the decision and the would-be delta are
  recorded, but no ELO delta and no evidence are written. Modes are `OFF`, `SHADOW`, `ENFORCE`.
- **Staff feedback loop**: moderators mark a flag (a flagged or shadow-flagged message) as
  `FALSE_POSITIVE` or `CONFIRMED` in the panel. The label, the scores, the model version and, for
  flagged messages only, the message text are stored as a labelled sample for later tuning, with a
  fixed retention. Changing ELO after a label is a separate staff action (see `design.md`, open
  questions).
- **Fail-open**: a scorer timeout, error or open circuit breaker records no delta and never
  blocks, delays or cancels chat delivery.
- **Metrics** (Micrometer, Prometheus): request outcomes, scorer latency, flags per section and
  category, labels by staff, and the circuit-breaker state. No label carries an owner hash or text.
- **Velocity** submits the chat signal asynchronously. Today `PlayerChatListener.submitChatSignal`
  makes the HTTP call on the chat event thread.
- **Client spec**: `POST /elo/chat` gains an optional `categories` map and `mode`. New routes for
  flags, labels, thresholds and mode. The spec moves from `0.0.44` to the next free version at
  implementation time.

Not BREAKING. Every change to the generated client and to the Velocity plugin is additive: new
optional response fields, new routes, and an asynchronous submit that Velocity already tolerates.
No enum value changes.

## Capabilities

### New Capabilities

None. The scorer, normalisation, shadow mode and feedback belong to the existing `elo-scoring`
capability. Thresholds and the feedback queue are admin screens.

### Modified Capabilities

- `elo-scoring`: the chat toxicity requirement (scorer, categories, normalisation, fail-open,
  shadow and enforce modes, per-category per-section thresholds, labelled samples, metrics).
  The rule-based scorer requirement is removed. The "chat evidence stores only a hash" requirement
  gains the labelled-sample exception.
- `admin-web-ui`: a chat toxicity panel for thresholds and mode per section (ADMIN); a feedback
  action on flagged and shadow-flagged history entries (MODERATOR and above); a labelled-sample
  list with CSV export (ADMIN).

## Prerequisite

This change needs the decision in `amend-data-protection-principle` (proposed 2026-10-28 by the
maintainer). Storing message text for labelled samples is personal data. It is only allowed under
the amended legitimate-interest basis, with a fixed retention. That change must merge first. Its
text is the basis for the labelled-sample exception here.

## Impact

- **Backend**: `elo/` (new scorer adapter, normalisation, threshold and mode resolution, shadow and
  enforce paths in `ChatToxicityService`, circuit breaker and micro-batcher), `elo/controller`
  (new routes, thin), a new `ChatToxicityFeedback` service in `elo/`, sweeper for samples,
  `application.yml` (`blackhole.elo.chat.*`).
- **Database**: one new Flyway migration `V<next>__chat_toxicity.sql` (`chat_toxicity_threshold`,
  `chat_toxicity_flag`). Additive only. Existing ELO history and evidence rows are untouched.
- **Inference service**: a new container in the cluster (own Helm values in the deploy chart),
  a model artifact, and a pinned image digest. It holds no state and no chat history.
- **Velocity**: `PlayerChatListener` submits off the event thread. No config change.
- **Web**: thresholds and mode panel, feedback buttons, labelled-sample list and CSV export.
- **Client**: additive spec version.

## Out of scope

- Training or fine-tuning our own model. This change integrates a published model and measures it.
  Training is a later decision, based on the labelled samples this change collects.
- SaaS moderation APIs. They are rejected for the default path (`design.md`, decision 1), and an
  adapter stays possible only after a data-processing review.
- Automatic ELO reversal when staff mark a false positive. Staff rollback is a separate change
  (`Should` in the requirement list).
- Voice, images or cross-chat context (conversation threads). The scorer sees one message at a time.
- Localised player-facing messages for toxicity flags. Existing screens are unchanged.
- Changing the mute check or the appeal flow.
- Rate limiting of the scoring endpoint. Infra owns rate limiting (`config.yaml` principle 5).

Ships as: feat(elo): score chat toxicity with a multilingual model
