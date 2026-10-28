## 1. Spike: model, quantisation and latency (no production code)

- [ ] 1.1 Load `textdetox/xlmr-large-toxicity-classifier` (v1) and v2 on the target node class. Record German and English F1 on one shared sample (labelled-sample set or a public German toxicity set). Record the result in the PR description.
- [ ] 1.2 Export both candidates to ONNX, quantise int8 (dynamic), and compare F1 against fp32. Accept int8 only with a drop of at most one F1 point.
- [ ] 1.3 Benchmark batch sizes 1, 4, 8 and 16 at 2 and 4 intra-op threads. Record p50, p99, RAM per replica and CPU at 100 messages per second. Check against the gate: p99 and RAM per replica, as in ADR-CORV-0006.
- [ ] 1.4 Check the Hugging Face Text Embeddings Inference CPU path for int8 XLM-RoBERTa sequence classification. Record yes or no with the image tag used. If yes within the gate, decide between TEI and the Python service in the PR description.
- [ ] 1.5 Review the openrail++ licence of the v1 model. Record the decision (adopt v1, use v2, or stop).
- [ ] 1.6 Replace the estimates in `design.md` (decision 1 table) with measured numbers, in the PR description of this task only. No file under `openspec/` changes in the code PR.

## 2. Inference service (Python, ONNX Runtime, CPU)

- [ ] 2.1 Red: `services/toxicity/tests/test_batch_endpoint.py` asserts that a batch of 3 texts returns 3 results, each with an overall score in `[0, 1]`, and that an empty batch returns 400.
- [ ] 2.2 Red: `services/toxicity/tests/test_threads.py` asserts that `intra_op` threads equal the container CPU limit read from the cgroup file path given as a parameter (fake file in `tmp_path`, no real cgroup).
- [ ] 2.3 Red: `services/toxicity/tests/test_no_text_in_logs.py` captures log records during a scoring call with a known sentinel text and asserts the sentinel never appears.
- [ ] 2.4 Implement the batch endpoint `POST /v1/score` with the model loaded once at start, the int8 model artifact path from configuration, and a health endpoint. No state between requests.
- [ ] 2.5 Add the Dockerfile with a pinned base image digest and a non-root user, and the model artifact fetched at build time with a checksum check.
- [ ] 2.6 Add the Helm values and templates for the deploy chart: two replicas, CPU limit 2, memory limit 1 GiB, readiness on the health endpoint, network policy allowing only the backend pods.

## 3. Backend: normalisation (pure functions)

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/NormalisationModelViewTest.java` asserts that `§a` colour codes and zero-width spaces are removed and NFKC is applied, and that leet is NOT decoded in the model view.
- [ ] 3.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/NormalisationTaggerViewTest.java` covers one case per rule in `design.md` decision 4: confusables, leet (`1d10t` to `idiot`), German `ß` and umlauts, repeated-letter collapse, squashed spacing (`i.d.i.o.t`), and the length-4 rule for squashed terms.
- [ ] 3.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/AllowListTest.java` asserts that an allow-listed game phrase suppresses a tagger match and does not change the model score.
- [ ] 3.4 Implement `ChatNormaliser` (two views) and `TermTagger` with the term lists from configuration. Both are immutable, with no static state.

## 4. Backend: scorer adapter, batching, timeout and breaker

- [ ] 4.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/ScorerBreakerTest.java` uses a fake clock. Five failures within ten seconds open the breaker, a call while open makes no scorer call, and a half-open probe after thirty seconds decides the state.
- [ ] 4.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/ScorerTimeoutTest.java` uses a fake scorer that never answers and asserts the outcome is `timeout` after the configured timeout, with no delta. The test uses a controllable scheduler, not a sleep.
- [ ] 4.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/MicroBatcherTest.java` asserts that 16 concurrent requests are sent as one batch, and that a lone request is sent after the linger with a fake clock.
- [ ] 4.4 Implement `HttpToxicityScorer` (implements `ToxicityScorer`, fixed URL from `blackhole.elo.chat.scorer.url`), `ScorerCircuitBreaker` and `MicroBatcher`. Delete `RuleBasedToxicityScorer` and its tests.
- [ ] 4.5 Add `application.yml` entries for `blackhole.elo.chat.scorer.*` (url, timeout, batch, breaker) and the normalisation options, each with an env-var default and a comment that states the intent.

## 5. Backend: thresholds, modes and persistence

- [ ] 5.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/ThresholdResolverTest.java` asserts that a category without a row uses the configured default, that a stored row wins, and that a threshold outside `[0, 1]` is refused.
- [ ] 5.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/ChatToxicityServiceModeTest.java` asserts that `OFF` makes no scorer call, `SHADOW` writes one `chat_toxicity_flag` row with `mode = SHADOW` and no ELO delta and no evidence, and `ENFORCE` applies the delta and writes the hash-only evidence as before.
- [ ] 5.3 Add `backend/src/main/resources/db/migration/V<next>__chat_toxicity.sql` (next free version, V27 or higher at implementation): `chat_toxicity_threshold (section, category, threshold, mode, updated_at, updated_by)` and `chat_toxicity_flag (id, owner_hash, section, mode, model_version, scores json, would_delta, label, labelled_by, labelled_at, message_text, created_at)`, with an index on `(section, created_at)`. No existing rows change.
- [ ] 5.4 Implement `ChatToxicityThresholdService` (a `@Singleton` in `elo/toxicity`) with the Error variant for expected failures, and the repository it owns. Thresholds and mode are read through a cache with a TTL (`blackhole.cache.*` pattern).
- [ ] 5.5 Change `ChatToxicityService.evaluate` to choose the path by mode. Keep the existing `EloDTO.ChatSignalResponse` fields and add the optional `categories` and `mode`.

## 6. Backend: staff feedback and sample retention

- [ ] 6.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/ChatToxicityFeedbackServiceTest.java` asserts that a label can be set once per flag and that a second label overwrites it with the new actor and time, using a fixed `Clock`.
- [ ] 6.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/SampleRetentionSweeperTest.java` asserts that rows older than `sample-retention-days` lose their `message_text` and are removed, and that rows inside retention are untouched.
- [ ] 6.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/FeedbackNoLeakTest.java` asserts that the CSV export contains no message text for rows without a label, and no name or UUID anywhere.
- [ ] 6.4 Implement the feedback service, the sweeper (cron from `blackhole.elo.chat.sample-retention-sweep-cron`) and the CSV export (ADMIN only).
- [ ] 6.5 Metrics: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/toxicity/ChatToxicityMetricsTest.java` uses `SimpleMeterRegistry` and asserts the outcome counters and the breaker gauge, and that no meter has an owner or text tag.

## 7. Backend: HTTP API and OpenAPI client

- [ ] 7.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/elo/controller/ChatToxicityApiContractTest.java` asserts the routes `GET /elo/chat/flags`, `PUT /elo/chat/flags/{id}/label`, `GET /elo/chat/thresholds/{section}`, `PUT /elo/chat/thresholds/{section}` and `PUT /elo/chat/mode/{section}` exist with the expected roles.
- [ ] 7.2 Add the routes in `ChatToxicityApi` (OpenAPI annotations here, not on the controller) and a thin `ChatToxicityController` with one service call per method. Request and response DTOs follow the sealed marker interface contract.
- [ ] 7.3 Regenerate the client: add `client/specs/blackhole-api-<next>.yml` (next version after 0.0.44), bump `inputSpec` in `client/build.gradle.kts`, and run `./gradlew :client:openApiGenerate`. Do not edit generated sources.

## 8. Velocity: asynchronous chat signal

- [ ] 8.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/listener/PlayerChatListenerAsyncTest.java` uses a fake executor and asserts that the chat event handler returns before the signal is sent, and that the mute check still runs synchronously.
- [ ] 8.2 Implement the bounded executor and the submission off the event thread in `PlayerChatListener.submitChatSignal`. Rejected submissions are logged without the message text.

## 9. Web: thresholds, mode, feedback and samples

- [ ] 9.1 Red: `web/src/components/elo/ChatToxicityPanel.spec.ts` (component test) asserts that the thresholds form refuses a value outside `[0, 1]` and that the mode control is hidden from a MODERATOR.
- [ ] 9.2 Red: `web/src/components/elo/FlagFeedback.spec.ts` asserts that the `FALSE_POSITIVE` and `CONFIRMED` buttons call the label endpoint once and show the saved label.
- [ ] 9.3 Implement the chat toxicity panel (thresholds and mode per section, ADMIN), the feedback buttons on flagged and shadow-flagged history entries, and the labelled-sample list with CSV export (ADMIN). Strings in `web/src/locales/en.json` and `de.json`.

## 10. Ship

- [ ] 10.1 Run `./gradlew clean build` for backend, velocity and client, `npm test` for web, and the service's pytest suite. Record the results in the PR description.
- [ ] 10.2 Open the code PR for the implementation (no file under `openspec/`) titled `feat(elo): score chat toxicity with a multilingual model`, with the template sections filled in from the measured numbers of task 1.6.
