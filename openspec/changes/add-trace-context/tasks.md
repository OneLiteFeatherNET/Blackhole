# Tasks

Small stacked pull requests, each based on the previous one and squash-merged; the PR title is the Conventional
Commit of that PR (one type each, see `proposal.md`). Every group starts with its failing tests, then the code.
Tests follow F.I.R.S.T.: spans are collected with an in-memory span exporter (`opentelemetry-sdk-testing`,
`InMemorySpanExporter`), logs with a captured logback appender, time through an injected `Clock`, no sleeps, no
network, no Docker unless the task says so. Each group runs its module's test task (`./gradlew :backend:test`,
`./gradlew :discord:test`, `./gradlew :velocity:test`, `npm test` in `portal/` and `web/`,
`deploy/charts/blackhole/tests/render.sh`) and `openspec validate --all --strict` before its PR is opened.

Order: 0 -> 1 -> 2 (after `switch-event-bus-to-nats` has landed) -> 3, 4, 5, 6 (independent after 1 and 2 for
the NATS-consuming ones) -> 7 -> 8.

## 0. Preconditions (no pull request)

- [ ] 0.1 Confirm `switch-event-bus-to-nats` is merged to `main`; group 2 starts from that state. Verify with `git log origin/main` and `grep -r NatsEventTransport backend/src/main`.
- [ ] 0.2 Pin the versions this change uses: read `libs.nats.jnats` in the catalog, `nitropack` in `web/package.json` and `portal/package.json`, and the OpenTelemetry BOM version the Micronaut platform resolves (`./gradlew :backend:dependencies --configuration runtimeClasspath | grep opentelemetry`). Write the results into `design.md` D1 and remove the "not verified" note for each one.
- [ ] 0.3 Confirm the Micronaut property name for the sampler (`OTEL_TRACES_SAMPLER` in the `otel.*` tree of Micronaut 4.10.2) and for the in-memory exporter in tests; record both in `design.md` D6.
- [ ] 0.4 Confirm the generated client's HTTP stack (`client/` and `ApiClient` in `org.openapitools`) has an extension point for request headers; record the exact hook in `design.md` D4.
- [ ] 0.5 Read the Alloy pipeline in Kubernetes-FLUX (`foundation/observability`, `clusters/feather-core/services-observability.yaml`) and confirm the OTLP receiver on port 4317; record the Alloy config path for the hand-off in group 7.

## 1. Backend: propagation, allow-list, sampler, log correlation (PR 1: `feat(backend): propagate w3c trace context and correlate logs`)

- [ ] 1.1 Failing test first: `TraceContextPropagationTest` sends a request with a known `traceparent` and `tracestate` through the Micronaut test server and asserts that the server span's trace id and parent span id equal the header's, and that the `tracestate` is kept; the `tracestate` assertion is the red step if the module does not keep it, otherwise the test documents the behaviour and is green from the start.
- [ ] 1.2 Failing test first: `OtelSamplerConfigTest` reads the effective sampler from the environment and asserts `parentbased_always_on` by default and the value of `OTEL_TRACES_SAMPLER_ARG` when set; add the `otel.traces.sampler` entry to `application.yml` with `${OTEL_TRACES_SAMPLER:parentbased_always_on}` and a comment; verify with `./gradlew :backend:test --tests "*OtelSamplerConfigTest"`.
- [ ] 1.3 Failing test first: `AttributeAllowListProcessorTest` gives a span an allowed attribute and a forbidden one (`url.full`, `client.address`, `db.statement`, `enduser.id`) and asserts only the allowed one is exported; then add `AttributeAllowListProcessor` (a `SpanProcessor` bean) with the list of `design.md` D7.
- [ ] 1.4 Failing test first: `SpanPrivacyScanTest` runs a request-driven scenario for a punishment route with a player UUID in its path, a report with a reporter hash, and a chat message request, collects the spans in memory and asserts no attribute value matches a UUID, an IPv4/IPv6 address, a Ban ID shape or a Discord snowflake; the test is the gate for all later groups, so it lives in `backend/src/test` with a shared helper that the other modules copy.
- [ ] 1.5 Failing test first: `JsonLogTraceCorrelationTest` attaches a captured logback appender, runs a request inside a span and asserts the JSON log line has `trace_id` and `span_id` equal to the span's; the existing `OpenTelemetryAppender` in `logback.xml` must make it pass, no change to the log format.
- [ ] 1.6 Run `./gradlew :backend:test` (all green) and `openspec validate --all --strict` before the PR is opened.

## 2. Backend: NATS headers (PR 2: `feat(backend): carry trace context on domain event messages`)

Starts after `switch-event-bus-to-nats` is on `main` (task 0.1). Branch from the up-to-date `main` plus PR 1.

- [ ] 2.1 Failing test first: `NatsEventTransportTraceTest` publishes a `DomainEvent` inside a span through a fake `JetStream` that records the `Message` and `PublishOptions`; assert the message has `traceparent` equal to the span's context, the body equals `mapper.writeValueAsBytes(event)` byte for byte, and `Nats-Msg-Id` still equals `eventId`.
- [ ] 2.2 Failing test first: `NatsEventTransportNoSpanTest` publishes without a current span and asserts that no `traceparent` header is written (no empty header, no exception).
- [ ] 2.3 Implement the injection in `NatsEventTransport.message` with the `TextMapPropagator` of `design.md` D3; tests 2.1 and 2.2 green; `./gradlew :backend:test`.
- [ ] 2.4 Failing test first: `NatsEventSubscriptionFactoryTraceTest` hands a message with `traceparent` to a handler and asserts the handler's span has the header's trace id as parent; a message without headers starts a new trace. Then implement the extraction in the pull loop (`NatsDelivery`), keeping the ack-after-handler rule.

## 3. Discord bot: consume, propagate and log (PR 3: `feat(discord): continue trace context from nats and into the backend`)

- [ ] 3.1 Verify the hook found in task 0.4 with a small test that builds an `ApiClient` with the interceptor and asserts it is invoked for one call (no code yet, the test fails to compile until the hook exists: this is the red step).
- [ ] 3.2 Failing test first: `NatsConsumerTraceTest` (fake `Message` with `traceparent`) asserts the consumer span's parent is the header's span; add the OpenTelemetry SDK (`opentelemetry-sdk`, `opentelemetry-exporter-otlp`, version from the BOM) and the span in `NatsConsumer`.
- [ ] 3.3 Failing test first: `BackendTraceInjectionTest` asserts that `HttpBackendGateway` calls carry a `traceparent` child of the current span, through a mock HTTP server in memory (no network port beyond loopback, or the `ApiClient` interceptor test double).
- [ ] 3.4 Failing test first: a log test (captured appender) for `trace_id` and `span_id`; add the `OpenTelemetryAppender` to `discord/src/main/resources/logback.xml`.
- [ ] 3.5 Failing test first: `DiscordSpanPrivacyScanTest` (copy of the helper of 1.4) over a notify and a case message; then `BotConfig` reads `OTEL_*` variables with the defaults of `design.md` D9 and documents them in `discord/README.md`.
- [ ] 3.6 Run `./gradlew :discord:test` green.

## 4. Velocity: propagate only (PR 4: `feat(velocity): propagate trace context from nats to the backend`)

- [ ] 4.1 Failing test first: `JnatsBusClientTraceTest` (existing fake connection pattern from `JnatsBusClientTest`) delivers a message with `traceparent` and asserts the subscription handler receives the context (through a thread-local-free holder passed to the handler, not a static).
- [ ] 4.2 Failing test first: `HttpBackendGatewayTraceTest` asserts that a call made while handling that message carries the same `traceparent` on the outgoing request; implement the interceptor from task 0.4.
- [ ] 4.3 Failing test first: `VelocityLogTraceFieldTest` asserts the trace id appears in the log line of the NATS sync service; implement with an explicit log field, no OTel SDK dependency.
- [ ] 4.4 Assert in a test that the plugin has no OTLP exporter on its classpath after relocation (`velocity/build.gradle.kts`, the `shadowJar` check); run `./gradlew :velocity:test` green.

## 5. Portal BFF: start the trace (PR 5: `feat(portal): start a trace in the backend-for-frontend`)

- [ ] 5.1 Failing test first: `bff.trace.test.ts` sends a request with an incoming `traceparent` and `baggage` and asserts the outgoing `fetch` to the backend carries a new `traceparent` whose trace id is not the incoming one and no `baggage` header.
- [ ] 5.2 Failing test first: a request without a trace header gets a new `traceparent` in the outgoing call.
- [ ] 5.3 Implement in `portal/server/lib/bff.ts`: a SERVER span per route (using the `ROUTES` template as span name), the `traceparent` header on the explicit header set, and the removal of incoming trace headers. Add the OpenTelemetry API and SDK packages named in task 0.2 to `portal/package.json`.
- [ ] 5.4 Run `npm test` in `portal/` green, including the existing `bff.test.ts`.

## 6. Web Nitro proxy: start the trace (PR 6: `feat(web): start a trace in the nitro proxy`)

- [ ] 6.1 Failing test first: `proxy.trace.test.ts` for `buildForwardHeaders`: incoming `traceparent`, `tracestate` and `baggage` are not in the result, and the result has the `traceparent` of the proxy span (the add to `NOT_FORWARDED` is the first failing assertion).
- [ ] 6.2 Failing test first: `forwardToBackend` sends a `traceparent` child of the request span, with an in-memory span exporter (`@opentelemetry/sdk-trace-base` `InMemorySpanExporter`).
- [ ] 6.3 Implement: add the three names to `NOT_FORWARDED` in `web/server/lib/proxy.ts`, a SERVER span in `web/server/lib/handlers.ts`, and the outgoing header. Pin the OpenTelemetry packages named in task 0.2.
- [ ] 6.4 Run `npm test` in `web/` green (including `proxy.test.ts` and `handlers.test.ts`).

## 7. Deployment (PR 7: `build(deploy): set the trace sampler and document the tracing settings`)

- [ ] 7.1 Failing test first: extend `deploy/charts/blackhole/tests/render.sh` to assert that with `tracing.enabled: true` the rendered configmap has `OTEL_TRACES_EXPORTER=otlp`, `OTEL_TRACES_SAMPLER=parentbased_always_on`, and that with `tracing.enabled: false` it has neither.
- [ ] 7.2 Add `tracing.sampler` to `values.yaml` and `values.schema.json`, render it in `backend-configmap.yaml`, and update the chart README values table.
- [ ] 7.3 Update the settings table in `docs/kubernetes.md` (the `OTEL_*` row and the sampler variables) and the "Read logs, traces and metrics" section with the propagation map of `design.md` D2 to D4.
- [ ] 7.4 Hand-off, not done here: open a pull request in Kubernetes-FLUX (`feat(observability): tail-sample blackhole traces in alloy`) with the tail policy of `design.md` D6 (errors, latency over 1 s, 10 % of the rest). Record its link in this change. Do not change Kubernetes-FLUX from this change's sessions without that PR.

## 8. Open the pull requests (last task)

- [ ] 8.1 Open one pull request per group 1 to 7 that has its own commits, in stack order, each with the base of the previous PR, using `gh pr create --base <previous branch or main>`. Each title is the Conventional Commit of its group (`feat(backend): ...`, `feat(backend): ...`, `feat(discord): ...`, `feat(velocity): ...`, `feat(portal): ...`, `feat(web): ...`, `build(deploy): ...`). Each body lists the tests added and ends with the line `https://claude.ai/code/session_01TFcvDovaYNHZewdbM2f8Ru`. Do not merge. Verify with `gh pr list --head <branch>`.

## Workflow follow-up

- Archive after the last PR is merged: `openspec archive add-trace-context`, committed as `docs(openspec): archive add-trace-context` unless shipped with the implementation.
- Keep `.claude/PROGRESS.md` current while the groups are in progress.
