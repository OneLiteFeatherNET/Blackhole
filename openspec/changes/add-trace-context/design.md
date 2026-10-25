# Design

## Context

Current state, read from the repository at `origin/main` (commit `01271c5`):

| Hop | What exists today |
|-----|-------------------|
| Browser to web (Nitro) | `web/server/lib/proxy.ts` `buildForwardHeaders` forwards every incoming header except hop-by-hop headers and a small deny-list (`host`, `expect`, `accept-encoding`, `x-forwarded-*`, `forwarded`). A browser `traceparent` therefore already reaches the backend unchanged. |
| Browser to portal (BFF) | `portal/server/lib/bff.ts` builds its backend headers explicitly (`accept`, `authorization`, `x-forwarded-for`, idempotency, `last-event-id`). No trace header is set or forwarded. |
| Backend HTTP server | `micronaut-tracing-opentelemetry-http` on the classpath; spans and OTLP export switch on with `OTEL_TRACES_EXPORTER=otlp` (`application.yml`, `otel.*`). `/health`, `/prometheus` and swagger paths are excluded. |
| Backend to NATS | `NatsEventTransport.message` builds a `NatsMessage` with subject and body only, plus `Nats-Msg-Id` through `PublishOptions`. No headers carry a trace. |
| NATS to Discord bot | `discord/.../notify/NatsConsumer` pulls JetStream messages and hands the body to the dispatcher. No span. |
| NATS to Velocity | `velocity/.../nats/JnatsBusClient` core subscriptions. No span. |
| Discord to backend | `discord/.../backend/HttpBackendGateway` wraps the generated `ApiClient`. |
| Velocity to backend | `velocity/.../punish/HttpBackendGateway` wraps the generated `ApiClient`. |
| Logs | Backend `logback.xml` already wraps the STDOUT appender with `io.opentelemetry.instrumentation.logback.mdc.v1_0.OpenTelemetryAppender`, so `trace_id` and `span_id` reach the JSON output when a span is current. The bot has no such appender. |
| Helm | `tracing.enabled: true`, `endpoint: http://alloy-receiver.grafana.svc:4317`, `serviceName: blackhole`; the configmap sets `OTEL_TRACES_EXPORTER=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`. No sampler variables. |
| Kubernetes-FLUX | Alloy, Tempo and Mimir run in the observability stack (`clusters/feather-core/services-observability.yaml`). The Alloy pipeline itself was not read in this change. |

## Goals / Non-Goals

**Goals**
- One request is one trace from the proxy to the bot and to Velocity, where those hops are reached.
- Every hop uses W3C Trace Context; Baggage is not used unless a hop needs it.
- No personal data (Ban ID, raw UUID, IP, message text) in spans, baggage or trace-correlated logs.
- Export is off in local development and on in the cluster; sampling is explicit in production.

**Non-Goals**
- Browser instrumentation, metrics, log shipping, dashboards, alerts.
- Trusting client-supplied trace context for anything but correlation.
- Changing the NATS event envelope, subjects or stream (owned by `switch-event-bus-to-nats`).

## Decisions

### D1. Libraries and versions

Pinned in this repository and verified by reading the files:

- Micronaut **4.10.2** (`openspec/config.yaml`, stack line).
- `micronaut-tracing-opentelemetry-http` and `micronaut-tracing-opentelemetry-jdbc` (`backend/build.gradle.kts`,
  `mn.*` aliases, version from the Micronaut platform BOM).
- `io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0` **2.20.1-alpha** (`settings.gradle.kts`,
  `opentelemetry-instrumentation-alpha`).
- `io.opentelemetry:opentelemetry-exporter-otlp`, version managed by the Micronaut platform BOM (`settings.gradle.kts`,
  `withoutVersion()`).

Found in documentation search and consistent with the Micronaut guides (not version-checked):
- Micronaut's OpenTelemetry integration covers HTTP server and HTTP client spans once
  `micronaut-tracing-opentelemetry-http` is declared; the guides for Micronaut 4.10.x document it. The guides do not
  show the propagator header name, so this change verifies the actual `traceparent` on the wire in the backend
  tests (task 1.1), not by reading the docs.
- The `opentelemetry-sdk-testing` artifact provides `InMemorySpanExporter` for span tests. Version from the BOM.

Not verified, to be checked when the PR is written: the jnats version (`libs.nats.jnats`, pinned in the catalog),
the h3 and nitropack version for the Nitro proxy (`nitropack 2.13.5` in `web/package.json` and `portal/package.json`),
and the OpenTelemetry JS SDK packages for Nitro. Nothing in this design claims a version of those.

**Agent vs manual instrumentation.**
- **Backend:** Micronaut tracing modules, no Java agent (the Micronaut modules already instrument Micronaut HTTP and
  JDBC; a second agent would double-instrument).
- **Discord bot:** manual SDK (`opentelemetry-sdk`, `opentelemetry-exporter-otlp`) inside the bot's own `Main`, with
  explicit spans around the NATS receive and the backend client call. The bot is a plain Java app without Micronaut;
  the Java agent would also work but adds a second configuration point and a bytecode agent to a small bot image.
- **Velocity:** no exporter in this change (D5); manual propagation only.

### D2. Trace start points and the browser

The web Nitro proxy and the portal BFF each start a SERVER span per request and create the outgoing backend request
with a fresh `traceparent` child of that span.

Incoming `traceparent`, `tracestate` and `baggage` headers from the browser are **removed** before forwarding. Today
`buildForwardHeaders` would pass them through. A browser (or a script on a public page) that controls the header
could make traces appear under a chosen ID or force sampling; this is a correlation problem, not an authorization
one, but it costs storage and hides real traces. Adding the three names to the `NOT_FORWARDED` deny-list of
`web/server/lib/proxy.ts` and the explicit header copy of `portal/server/lib/bff.ts` is the enforcement point. This
is the one place where the brief's "allow-list" wording is wrong: the web proxy is a deny-list, so the trace headers
must be added to it, not added to an allow-list.

### D3. NATS headers

The backend writes the current span context into the JetStream message headers:

```java
Headers headers = new Headers();
TextMapSetter<Headers> setter = (carrier, key, value) -> carrier.put(key, value);
GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
        .inject(Context.current(), headers, setter);
Message message = NatsMessage.builder().subject(subject).data(body).headers(headers).build();
```

jnats supports `Headers` on `NatsMessage.Builder` and on the `JetStream.publishAsync(message, options)` overload, as the
jnats documentation shows. The headers stay separate from the envelope: the `DomainEvent` JSON is byte-identical
to the design of `switch-event-bus-to-nats`.

Consumers read the headers with `message.getHeaders()` (or the `Headers` value that the consumer adapter already
receives), build a `Context` with the `TextMapGetter` over those headers, and run the handler inside a CONSUMER span
with the extracted parent. Messages without the headers (published by an older backend) start a new root span.

Header names: `traceparent` and `tracestate` only. `Nats-*` names stay reserved for JetStream.

### D4. Propagation on the HTTP hops

- Backend (Micronaut server) extracts `traceparent` automatically. No code.
- Discord bot and Velocity call the backend through the generated `ApiClient`. Each adds a request interceptor
  (`ApiClient` configuration in `HttpBackendGateway`) that injects the current context. The generated client's HTTP
  stack is not read in this change; the interceptor is an extension point of the client invoker, verified in task 3.1
  before the code is written.
- Nitro and the BFF inject into their outgoing `fetch` calls (`headers` from `buildForwardHeaders` plus the injected
  `traceparent`).

### D5. Velocity export

Velocity is outside the cluster. In this change it **propagates only**: it reads `traceparent` from NATS messages,
puts `trace_id` into its own log lines and injects the context into its backend calls, but it creates no spans and
has no exporter. This keeps the proxy free of an outbound observability endpoint on the public internet. Open
question 1 covers the alternative.

### D6. Sampling

| Environment | Head sampler (SDK) | Tail sampling (Alloy) | Export |
|-------------|--------------------|-----------------------|--------|
| Production | `parentbased_always_on` | keep every trace with an error span, keep traces longer than 1 s, keep 10% of the others | `OTEL_TRACES_EXPORTER=otlp` |
| Development (`blackhole-dev`) | `parentbased_always_on` | none | `otlp` |
| Local | `parentbased_always_on` | none | `none` (default) |

Tail sampling needs all spans of a trace at the collector, so the SDK in production does not drop them at the head.
The head rate must not be lowered below 1.0 while the Alloy tail policy depends on it. The sampler variables are the
standard OpenTelemetry environment variables `OTEL_TRACES_SAMPLER` and `OTEL_TRACES_SAMPLER_ARG`; the Micronaut
property name for them is verified in task 1.2 before it is written into `application.yml`.

The tail policy lives in Alloy (Kubernetes-FLUX) and is a hand-off, not code in this repository.

### D7. Attribute allow-list and GDPR

Allowed span attributes, enforced by a `SpanProcessor` in the backend, the bot and the Velocity SDK, and by the
span configuration of the Micronaut instrumentation:

| Attribute | Source | Rule |
|-----------|--------|------|
| `service.name`, `service.version`, `deployment.environment.name` | resource | fixed values |
| `http.request.method`, `http.response.status_code` | HTTP server and client | as is |
| `http.route` | HTTP server | the route template only (`/punishments/{id}`), never the matched path |
| `url.template` | HTTP client | the template only |
| `error.type` | any | the exception class name only |
| `messaging.system` | NATS | `nats` |
| `messaging.operation.type` | NATS | `publish`, `receive` |
| `messaging.destination.name` | NATS | the subject (`blackhole.events.<event type>`), which holds no identifier |
| `blackhole.event.type` | publisher and consumer | the event type |
| `blackhole.component` | all | `backend`, `discord`, `velocity`, `web`, `portal` |
| `db.system` | JDBC | `postgresql` |
| `db.operation.name` | JDBC | the SQL verb only |

Everything else is removed, in particular: `url.full`, `url.path`, `url.query`, `client.address`, `net.peer.*`,
`user_agent.original`, `http.request.header.*`, the JDBC `db.statement` (parameters), `enduser.id`, `discord.*`
ids, and any attribute a dependency adds. Baggage is not set at all.

**Test that scans spans (task 1.4 and the same in each module):** an in-memory span exporter collects the spans of a
request-driven test; the test fails if any attribute value matches a Ban ID shape (8 characters from the Ban ID
alphabet), a UUID, an IPv4 or IPv6 address, a Discord snowflake (17 to 20 digits) or any value outside the
allow-list keys. Message content is never a span attribute by construction; the test covers it with a
chat-message request.

### D8. Logs

Backend: the existing `OpenTelemetryAppender` already writes `trace_id` and `span_id` into the MDC; the change adds
a test that asserts the JSON line of a request carries both fields (captured appender, not stdout parsing).

Discord bot: adds the same appender to `discord/src/main/resources/logback.xml`. Velocity: writes the trace id into
its own log lines as an explicit field; it has no logback MDC appender in this change.

### D9. Configuration

Documented in `application.yml` (`otel.*`, with `${ENV:default}` pattern and a comment):

- `OTEL_TRACES_EXPORTER` (existing, default `none`).
- `OTEL_EXPORTER_OTLP_ENDPOINT` (existing, default `http://localhost:4317`).
- `OTEL_SERVICE_NAME` (existing, default `blackhole`).
- `OTEL_TRACES_SAMPLER` (new, default `parentbased_always_on`), `OTEL_TRACES_SAMPLER_ARG` (new, unset).

Helm (`deploy/charts/blackhole/values.yaml`, `tracing`): add `sampler` and document the production choice. The
configmap template renders the sampler variables only when `tracing.enabled` is true. The chart keeps
`tracing.enabled: true` for the cluster; local runs keep the default `none`.

The bot and the proxies read the same variable names. Off by default locally: no variable is set in
`docker/docker-compose.yml`.

### D10. Dependencies on other changes

- `switch-event-bus-to-nats` must land first: D3 writes into the NATS transport that change creates
  (`NatsEventTransport`, `NatsEventSubscriptionFactory`). Until then the backend has only the RabbitMQ path.
- `deploy-to-kubernetes` owns `deploy/charts/blackhole`; the sampler variables are added to it in group 7.

## Risks / Trade-offs

- **Head sampling at 100 % costs storage** in production. Mitigation: the tail policy drops the bulk at Alloy, and
  the allow-list keeps spans small. Revisit after one week of data.
- **Wrong attribute leaks through a new dependency.** Mitigation: the span processor drops unknown attributes, and
  the scanning test runs in CI on every module.
- **Trust of the browser header removed.** A user who expects to see their own browser trace will not. Accepted.
- **The privacy principle in `openspec/config.yaml` and the authentication principle disagree** (principle 2 says
  every route needs an Entra token; `CLAUDE.md` says there is no auth layer). This change does not depend on
  either; the actor's identity is never a span attribute in any case.

## Migration Plan

Each pull request is additive and can be reverted alone. Rollback: set `OTEL_TRACES_EXPORTER=none` (no code path is
removed). No database, API or schema change, so no Flyway migration and no client spec regeneration.

## Open Questions

1. **Velocity export (D5):** propagate-only, or OTLP/HTTPS to a public endpoint? Default: propagate-only; exporting
   from the proxy needs a public, authenticated OTLP ingest that does not exist yet.
2. **Browser trace start (D2):** start at Nitro/BFF and drop browser trace headers, or add the browser SDK? Default:
   start at Nitro/BFF and drop browser trace headers.
3. **Tail-sampling home (D6):** the policy goes into Alloy in Kubernetes-FLUX, or a per-service sampler is enough?
   Default: Alloy tail policy in a separate Kubernetes-FLUX pull request; services keep `parentbased_always_on`.
4. **Bot export (D1):** should the Discord bot export spans to Alloy, or only propagate? Default: export, because it
   runs in the cluster next to Alloy and is the consumer that most needs a trace of an appeal.
