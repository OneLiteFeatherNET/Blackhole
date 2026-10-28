## Context

`POST /elo/chat` (`elo/controller/EloController`, `EloApi`) receives a SHA-512 owner hash and a
message of at most 512 characters from the Velocity plugin (`PlayerChatListener.onChat`). The
backend hands the text to `ChatToxicityService.evaluate`, which asks a `ToxicityScorer`. A score at
or above `blackhole.elo.chat.flag-threshold` (default 0.3) applies a CHAT delta of
`flag-delta * score` (default -50) with reason `TOXICITY_FLAG` and stores a hash-only
`CHAT_MESSAGE` evidence record.

The only implementation is `RuleBasedToxicityScorer` (`elo/RuleBasedToxicityScorer.java`), a
substring counter over `blackhole.elo.chat.toxic-patterns`. Its own comment calls it a placeholder.
The existing `elo-scoring` spec makes it the default scorer, with `ToxicityScorer` as the seam for
replacement.

Two facts shape the design:

- The Velocity call is synchronous on the chat event thread (`submitChatSignal` runs after the mute
  check and blocks until the HTTP call returns). A slow scorer would delay every chat message.
- Chat ELO is automatic. A wrong flag can lower a score below the soft threshold and apply a
  temporary mute without a moderator online (`config.yaml` principle 4). Changes must therefore
  run in shadow first and be measurable.

Principles that apply (`openspec/config.yaml`): GDPR-by-design (principle 1; amended for labelled
samples, see decision 7 and the prerequisite in `proposal.md`), no auth layer added (principle 2),
thin controllers and a service per feature package (principle 3), dual-ELO stays automatic and
contestable (principle 4), infra owns rate limiting (principle 5).

### Volume assumption

No production chat metrics are in this repository. The estimates below assume a peak of 1,000
concurrent players at about 0.1 message per second each, so 100 messages per second at peak and
about 30 per second on average. The shadow phase (decision 6) replaces this assumption with
measured numbers before any enforce decision.

## Goals / Non-goals

Goals:

- Score German and English chat with a model that was trained on German data, behind the existing
  `ToxicityScorer` seam.
- Return four category scores (`INSULT`, `THREAT`, `SEXUAL`, `HATE`) next to the overall score.
- Make the normalisation of Minecraft slang and obfuscation a tested, configurable step.
- Thresholds per category and per section, editable by admins.
- Shadow mode per section, with a rollback that needs no redeploy.
- A feedback loop: staff labels flags, labels are stored for tuning.
- Fail-open: no scorer outage changes chat or ELO beyond a skipped delta.

Non-goals: training a model (out of scope), SaaS moderation on the default path, automatic ELO
reversal on a false-positive label, context-aware scoring across messages.

## Decisions

### 1. Scoring engine: a self-hosted inference service, reached over HTTP

The backend calls one fixed, configured URL (`blackhole.elo.chat.scorer.url`), with no
caller-supplied URL, so no SSRF surface is added. The service runs in the cluster, serves the model
from ONNX Runtime on CPU, and is stateless.

Options compared:

| Option | Latency at 100 msg/s peak (estimate) | CPU | Memory per pod (estimate) | Data leaves network | Verdict |
|---|---|---|---|---|---|
| (a) In-JVM ONNX Runtime (`com.microsoft.onnxruntime`) in the backend | +10-20 ms per message, no hop | shares backend pods | 0.6 GiB int8 model in the backend heap | no | Rejected for now (see below) |
| (b) Self-hosted ONNX Runtime service in the cluster | 8-15 ms per message batched, plus 2-5 ms network | 2 vCPU busy at peak, 2 replicas | 0.6 GiB int8 weights, 1 GiB limit | no | Recommended |
| (c) SaaS moderation APIs | network round trip, 100-300 ms per call, rate limits | none ours | none | yes, every chat message | Rejected for the default path |

Estimates, not measurements. The spike (tasks group 1) measures them on the target node class.
They assume an XLM-RoBERTa-large sized model, int8 weights, batches of 16 short messages
(about 48 tokens each) and 2 intra-op threads per replica.

Recommended form of (b): a small Python service on ONNX Runtime with the model int8-quantised
(dynamic quantisation, as in the precedent below), an explicit `intra_op` thread count set from the
container CPU limit, and one batch endpoint. The backend also keeps the existing single-message
contract.

Precedent: Corvus ADR-CORV-0006, "ONNX Runtime auf CPU im Python-Scoring-Service"
(https://outline.onelitefeather.dev/doc/adr-corv-0006-onnx-runtime-auf-cpu-im-python-scoring-service-5rcJ33JIV4,
status proposed, not yet accepted). It makes the same choice for the same reasons: CPU-only ONNX
Runtime, int8 quantisation, threads pinned to the cgroup limit (ORT otherwise uses the host cores),
a p99 and RAM gate per model version, and a pod limit of about 4 CPU and 1 GiB. Our reuse is
pattern-level only, for three reasons:

- Corvus scores windowed behaviour features on JetStream. We score single chat messages that
  contain player text. Different data, different consumers, different failure modes.
- Corvus is still proposed. We should not block on its acceptance.
- A shared service would couple moderation availability to behaviour scoring and would make
  the Corvus release cadence govern our rollout.

Decision: a separate Blackhole deployment that copies the ADR's pattern: the same base image, the
same thread-pinning rule, the same int8 export recipe, and the same p99/RAM gate. If ADR-CORV-0006
is accepted with a shared Helm chart, adopt the chart, not the workload. Revisit if the spike shows
the pattern does not hold for this model.

Why not (a) now: the model is roughly 0.6 GiB quantised and about 2.2 GiB in fp32. Loading that
into the backend heap makes the backend pods much larger and couples scorer releases to backend
releases. The ONNX Runtime Java package exists (https://onnxruntime.ai/docs/get-started/with-java.html),
and the Corvus ADR keeps JVM inference as a later option. It stays the fallback if the separate
service cannot meet the gate.

Why not (c): the data-protection cost is decisive. Every chat message would leave the cluster.
- Google Perspective API ends service after 2026 with no migration support
  (https://perspectiveapi.com/, shutdown date 31 Dec 2026 stated on the page).
- OpenAI omni-moderation (https://openai.com/index/upgrading-the-moderation-API-with-our-new-multimodal-moderation-model/)
  is free, multilingual (tested on 40 languages per OpenAI) and returns categories. It sends each
  message to a third party outside the EU, which needs a transfer basis under GDPR chapter V.
- Azure AI Content Safety (https://learn.microsoft.com/en-us/azure/ai-services/content-safety/language-support)
  covers hate, sexual, violence and self-harm, and names German among its trained languages. Per-call
  cost, and data leaves the network.
A SaaS adapter stays possible behind the same interface after a data-processing review.

### 2. Model: TextDetox XLM-RoBERTa toxicity classifier, v1 as the candidate

Candidate: `textdetox/xlmr-large-toxicity-classifier` (v1,
https://huggingface.co/textdetox/xlmr-large-toxicity-classifier). It is XLM-RoBERTa based, covers
nine languages including German, reports German F1 0.8758 on its own test split, and is
licensed openrail++ (licence terms need review before use).

Rejected: Detoxify's multilingual model (`unitary/multilingual-toxic-xlm-roberta`). Its README
says it was trained on seven languages, English, French, Spanish, Italian, Portuguese, Turkish and
Russian, and should only be tested on those (https://github.com/unitaryai/detoxify). German is not
among them, so it does not meet the requirement.

Alternative: `textdetox/xlmr-large-toxicity-classifier-v2`
(https://huggingface.co/textdetox/xlmr-large-toxicity-classifier-v2). It covers 15 languages
including German, but its reported German F1 is 0.7325, below v1. The v2 card does not explain the
drop. v2 is the fallback if v1's licence fails review. The spike compares both on a German and
English sample from the labelled-sample set (see decision 7) and on a public German toxicity
set, and records the result.

The model outputs one toxic score. It does not output the four categories. See decision 3.

### 3. Category scores: the model's toxic verdict split by a term tagger (phase 1)

No multilingual model in the research above gives insult, threat, sexual and hate as separate
heads with German support. So phase 1 works in two steps:

1. The model returns the overall toxic probability `p`.
2. A tagger assigns `p` to the categories whose German or English terms match the normalised text
   (decision 4). With no term match the message is scored `INSULT` by default, since that is the
   common case. Each matched category gets `p`, not a split of `p`. Several matches give several
   categories, each at `p`.

Category thresholds then apply per category (decision 5). The term lists live in config, reviewed
by staff, versioned in `application.yml` as defaults and stored in the database per section.

Rejected: a multi-label SaaS model (data, decision 1); training our own multi-label head (out of
scope); an LLM classifier per message (CPU and latency cost far above budget). Phase 2 can replace
step 2 with a multi-label model once labelled samples exist (decision 7). The honest limitation:
phase 1 categories are only as good as the term lists, and a `SEXUAL` or `THREAT` label is a
guess until the tuning data says otherwise.

### 4. Normalisation: two views of the same message

The model and the tagger need different input, so the normalisation has two outputs.

Model view (light, the model is trained on natural text):

- remove Minecraft legacy colour and format codes (`§` followed by `0-9a-fk-or`), not `&` codes
  unless `chat.normalise.ampersand-codes` is enabled
- remove zero-width and other invisible characters, and control characters
- Unicode NFKC, then collapse runs of whitespace
- nothing else (no leet decoding, no lowercasing beyond what the model's tokenizer does)

Tagger view (full normalisation, applied in this order):

1. Model view steps.
2. Confusable mapping to Latin: Cyrillic and Greek look-alikes (`а`, `е`, `о`, `р`, `с`, `х`, `ο`).
3. Leet speak: `0`→o, `1`→i, `3`→e, `4`→a, `5`→s, `7`→t, `@`→a, `$`→s, `!`→i in a letter context only.
4. Lowercase, German case folding (`ß`→ss), umlaut variants both `ä` and `ae` tried.
5. Repeated-letter collapse to at most two (`sooo` becomes `soo`).
6. Spacing and punctuation inside a word: a second, squashed form with non-letters removed
   (`i.d.i.o.t` becomes `idiot`). A term matches on either form with word boundaries on the
   plain form and on the squashed form only for terms of length 4 or more, to keep false
   positives low.
7. Allow-list: per-section game phrases (for example `kill the dragon`, `bed wars`, `noob` as a
   name) that suppress tagger matches inside them. The allow-list suppresses tagger matches only,
   never the model score.

Each rule is a unit test with the input and expected output. The normalised text is never persisted;
the labelled-sample text (decision 7) is the original message, not the normalised one.

### 5. Thresholds per category and per section

Thresholds are stored in `chat_toxicity_threshold (section, category, threshold, updated_at,
updated_by)`. A row is created on first access from the `blackhole.elo.chat.*` defaults. A
category with no row uses the default. Defaults, to calibrate in shadow:

- overall flag threshold: `flag-threshold` (0.3 today, kept)
- per category: `INSULT` 0.5, `THREAT` 0.3, `SEXUAL` 0.5, `HATE` 0.4 (threats get a lower bar)

A message is flagged when any category score reaches that category's threshold. The ELO delta keeps
today's formula: `flag-delta * max(category scores)`, rounded. A category threshold is a number in
`[0, 1]`, and the API refuses anything else. Changes are audited (see the admin UI spec).

### 6. Shadow and enforce modes per section

Each section has a mode, stored in `chat_toxicity_threshold` alongside its thresholds (one row per
section with category `*`) and seeded from `blackhole.elo.chat.mode`:

- `OFF`: the scorer is not called.
- `SHADOW`: the scorer is called and the decision is recorded (`chat_toxicity_flag` row with
  `mode = SHADOW`, scores, model version and would-be delta). No ELO change, no evidence record,
  no player-facing effect. The message text is stored only for would-flag rows, under decision 7.
- `ENFORCE`: the flag applies the ELO delta and writes the hash-only evidence record, as today.

Only ADMIN changes the mode. Rollback is a mode change to `OFF` in the panel, effective within the
thresholds-cache TTL. No redeploy. Rollout gate, proposed: a section moves from `SHADOW` to
`ENFORCE` only after at least 14 days and at least 200 labelled shadow flags, with a
false-positive share of at most 15 percent. These numbers are proposals for the maintainer.

Shadow mode writes at most one row per flagged message. It writes no row per clean message, since
at 30 messages per second that is about 2.6 million rows a day. Clean-message volume is observed
through metrics only.

### 7. Staff feedback loop and labelled samples

On a flagged or shadow-flagged history entry, a moderator (MODERATOR and above) sets a label:
`FALSE_POSITIVE` or `CONFIRMED`, with the actor's Entra `oid` from the token (principle 2). The
label is written to `chat_toxicity_flag` with `labelled_by` and `labelled_at`.

For flagged and would-flag messages the backend stores the message text in
`chat_toxicity_flag.message_text`, with `sample-retention-days` (default 30). A sweeper deletes the
row, text included, after retention. The text is stored only for rows that need a label; clean
messages are never stored. Rejected alternatives:

- Hash-only labels: no text means no tuning data, so the labels could not train or evaluate
  anything later.
- Storing every message: about 2.6 million rows a day at 30 messages per second, and personal
  data with no use.
- Storing text encrypted with a key that the admin can read: adds key handling for no gain over
  retention and ADMIN-only access.

This is personal data (chat text). It needs the amended legitimate-interest basis from
`amend-data-protection-principle` (see `proposal.md`, Prerequisite). Access: ADMIN and MODERATOR
through the panel; the CSV export is ADMIN only. Export carries the hashed owner, never a name or a
UUID.

A label does not change ELO. Reversing a TOXICITY_FLAG delta after a false-positive label is a
staff action that belongs to the rollback change (open question 3).

### 8. Fail-open, timeouts and the circuit breaker

- The backend calls the scorer with a 250 ms timeout (`blackhole.elo.chat.scorer.timeout`), via a
  client with a connection pool. Velocity no longer waits for the backend (decision 10).
- Outcomes that record no delta and no flag: timeout, connection error, HTTP 5xx, malformed
  response, and an open breaker. Each writes one metric outcome and a log line without the message
  text (the log carries the outcome, the section and the model version).
- Circuit breaker: five failures within ten seconds open it for thirty seconds, then one half-open
  probe decides. While open, no call is made and the outcome is `breaker_open`.
- A scorer outage never blocks, delays or cancels chat delivery. Chat delivery was already decided
  by the mute check before the signal is sent (`PlayerChatListener`).

### 9. Batching in the backend

A micro-batcher in the backend collects concurrent scoring requests and sends them to the scorer as
one batch: at most 16 messages, or 5 ms of linger, whichever comes first
(`blackhole.elo.chat.scorer.batch.max-size`, `...linger`). Per-message calls remain the fallback at
low traffic. The batcher has no shared static state. Its clock is injected, and its tests drive it
with a fake clock and a fake scorer.

Rejected: one HTTP call per message (more round trips, lower throughput on the 2-vCPU replicas).

### 10. Velocity submits asynchronously

`PlayerChatListener.submitChatSignal` moves off the chat event thread (a bounded executor owned by
the plugin). The mute check stays synchronous, since it decides delivery. Only the signal submission
becomes asynchronous. A rejected submission is logged and dropped; the existing best-effort
behaviour already drops it.

### 11. Metrics

Micrometer, exported through the existing Prometheus registry:

- `blackhole.chat.toxicity.requests{outcome,section,mode}`: `scored`, `timeout`, `error`,
  `breaker_open`, `off`.
- `blackhole.chat.toxicity.scorer.duration{engine}`: timer, p50, p95, p99.
- `blackhole.chat.toxicity.flags{section,category,mode}`: flags per category.
- `blackhole.chat.toxicity.labels{section,label}`: staff labels.
- `blackhole.chat.toxicity.breaker.state`: gauge, 0 closed, 1 half-open, 2 open.

No label carries an owner hash, a player name or message text.

## Risks / Trade-offs

- **German slang and false positives.** The model may flag in-game banter or regional expressions.
  Mitigation: shadow mode, labels, allow-list, per-category thresholds. Residual risk: the tuning
  set is small at first.
- **Model licence.** v1 is openrail++, which has use restrictions. Review before adoption; v2 is
  the fallback.
- **Int8 quality loss.** Quantisation can lower F1. The spike compares fp32 and int8 on the same
  sample and accepts int8 only with a drop of at most one F1 point.
- **Pod sizing.** A 1 GiB pod may not hold the large model with headroom. The gate is p99 and RAM
  per replica, as in the Corvus ADR; if it fails, use the base-size model or the in-JVM fallback.
- **TEI as an alternative.** Hugging Face Text Embeddings Inference
  (https://huggingface.co/docs/text-embeddings-inference/supported_models) supports XLM-RoBERTa
  sequence classification and would need no Python code. Its CPU path for int8 weights is not
  verified here. The spike tests it; if it serves int8 within the gate, it replaces the Python
  service and nothing else changes.
- **Personal data in labels.** Covered by decision 7 and the amendment prerequisite. A missing
  amendment blocks the feedback loop, not the scorer.
- **Velocity asynchrony.** Signals can arrive out of order. Scores are per message and independent,
  and decay is reconciled before each delta, so order does not change the outcome.

## Migration plan

1. Deploy the inference service with no traffic. Health check and the spike benchmark pass.
2. Deploy the backend with every section in `OFF`. The rule-based scorer is removed in this
   step; `OFF` never calls a scorer, so no behaviour changes.
3. Set the `chat` section to `SHADOW`. Run at least 14 days. Label shadow flags in the panel.
4. Review the metrics and labels. Adjust thresholds and allow-list. Decide on enforce against the
   gate in decision 6.
5. Set `chat` to `ENFORCE`. Rollback is `OFF`, in the panel, without a redeploy.

Existing data: `TOXICITY_FLAG` history rows and hash-only `CHAT_MESSAGE` evidence stay as they are.
The new Flyway migration `V<next>__chat_toxicity.sql` (creates `chat_toxicity_threshold` and
`chat_toxicity_flag`) is additive. The next free version number is assigned at implementation time
(main is at V27 today). The client spec is the next version after the one in
`client/build.gradle.kts` (0.0.44 today), and the chosen number is assigned at implementation time.
This change is not BREAKING: the new response fields are optional and no enum value changes.

## Open questions

1. Model choice: v1 or v2 after the spike, and whether the licence of v1 is acceptable.
2. Gate numbers in decision 6 (14 days, 200 labelled flags, 15 percent) and the default thresholds
   in decision 5 are proposals. The maintainer sets them.
3. Should a `FALSE_POSITIVE` label reverse the TOXICITY_FLAG delta? Proposed: no, not in this
   change. Reversal belongs to the staff rollback change.
4. Is the labelled-sample text retention of 30 days right, and does it need a separate legal
   review after `amend-data-protection-principle`?
5. Is Corvus ADR-CORV-0006 accepted before this change ships? If yes, reuse its Helm chart pattern.
6. Phase 2: replace the term tagger with a multi-label model once labelled samples exist. Needs
   its own change and a licence check.

## References

- Detoxify, languages of the multilingual model: https://github.com/unitaryai/detoxify
- textdetox XLM-R toxicity classifier v1: https://huggingface.co/textdetox/xlmr-large-toxicity-classifier
- textdetox XLM-R toxicity classifier v2: https://huggingface.co/textdetox/xlmr-large-toxicity-classifier-v2
- Google Perspective API sunset: https://perspectiveapi.com/
- OpenAI moderation model (multilingual): https://openai.com/index/upgrading-the-moderation-API-with-our-new-multimodal-moderation-model/
- Azure AI Content Safety language support: https://learn.microsoft.com/en-us/azure/ai-services/content-safety/language-support
- ONNX Runtime Java: https://onnxruntime.ai/docs/get-started/with-java.html
- Hugging Face Text Embeddings Inference, supported models: https://huggingface.co/docs/text-embeddings-inference/supported_models
- Corvus ADR-CORV-0006: https://outline.onelitefeather.dev/doc/adr-corv-0006-onnx-runtime-auf-cpu-im-python-scoring-service-5rcJ33JIV4
