# Proposal

**Ships as:** `feat(backend)`, `feat(discord)`, `feat(velocity)`, `feat(portal)`, `feat(web)` and `build(deploy)`,
one Conventional Commits type per pull request, stacked in the order of `tasks.md`. The change adds no product
behaviour a player or moderator sees; it adds trace context that every hop carries and that the cluster observes.

## Why

A single action, such as a staff member resolving an appeal in the admin UI, crosses the browser, the web or
portal proxy, the backend, NATS JetStream, the Discord bot and, through the proxy's NATS subscription, Velocity.
Today a slow or failed request can only be followed by hand, through timestamps in four log streams. The cluster
already runs Grafana Alloy, Tempo and Mimir and the backend already exports OTLP to `alloy-receiver.grafana.svc:4317`
(`deploy/charts/blackhole/values.yaml`, `tracing`), but only the backend emits spans, and nothing carries the
trace across the NATS hop or the proxies. The privacy rules are the constraint: a span is data that leaves the
process, so it must hold no Ban ID, raw UUID, IP address or message content (principle 1 of `openspec/config.yaml`).

## What Changes

- **W3C Trace Context on every hop.** `traceparent` and `tracestate` are propagated from the edge to the backend,
  from the backend to NATS and from NATS to the Discord bot and Velocity. The W3C Baggage header is **not** used
  by default; it is added only if a later hop needs a value the span attributes cannot carry (none is known today).
- **Trace start points.** The web Nitro proxy and the portal BFF start a server span for each request and do not
  trust an incoming `traceparent` from the browser (see design D2). The browser is not instrumented.
- **NATS message headers.** The backend's `NatsEventTransport` writes `traceparent` and `tracestate` into the JetStream
  message headers; the Discord `NatsConsumer` and the Velocity subscription read them and continue the trace.
  The `Nats-` header prefix stays reserved for JetStream; trace headers use the W3C names.
- **Sampling.** Head sampling is `parentbased_always_on` in production (every span reaches Alloy) with tail sampling
  at the Alloy edge (keep errors, keep slow traces, keep 10% of the rest). Development samples always on. Local
  development exports nothing (`OTEL_TRACES_EXPORTER` stays `none`).
- **Attribute allow-list.** Span and baggage attributes are restricted to an allow-list defined in `design.md`,
  enforced by a span processor in each service and checked by a test that scans in-memory spans for Ban IDs,
  UUIDs, IP addresses and Discord snowflakes.
- **Log correlation.** `trace_id` and `span_id` appear in the JSON logs of the backend and the Discord bot.
  The backend already has the OpenTelemetry logback MDC appender; the bot gains the same.
- **Velocity** only propagates the context to the backend and reads it from NATS; it exports no spans in this
  change (open question 1).

**Out of scope:** the browser OpenTelemetry SDK; metrics and log shipping (Mimir and Loki stay as they are); a
trace UI or dashboards; baggage-based business context; instrumenting the Velocity proxy with an exporter
(see open questions); changing the tail-sampling policy of the Alloy stack beyond the rules listed here, which
live in Kubernetes-FLUX and are a separate change there; any HTTP API, OpenAPI spec, generated client or Flyway
migration change.

**BREAKING:** none. Trace headers are additive. The Velocity plugin and the generated client keep their API; the
client gains an optional header interceptor only.

## Capabilities

### New Capabilities
- `distributed-tracing`: how a request is traced end to end across the browser proxies, the backend, the NATS
  event bus, the Discord bot and Velocity: propagation, sampling, the span attribute allow-list, log correlation
  and the configuration that switches export on.

### Modified Capabilities
- None in `openspec/specs/`. The event bus (`domain-event-bus`) and the Kubernetes deployment exist only as
  in-flight changes (`switch-event-bus-to-nats`, `deploy-to-kubernetes`). This change adds its NATS header
  requirements to `distributed-tracing` and lists the dependency on those changes in `design.md` (D10); it does
  not write a delta against a spec that is not yet archived.

## Impact

- **Backend** (`backend/`): `micronaut-tracing-opentelemetry-http` and `...-jdbc` are already on the classpath
  (`backend/build.gradle.kts`); the change adds the NATS header injection in `NatsEventTransport`, the allow-list
  span processor and the tests. Micronaut 4.10.2 is pinned by `openspec/config.yaml`.
- **Discord bot** (`discord/`): new OpenTelemetry SDK and OTLP exporter dependency, JSON log correlation in
  `logback.xml`, `NatsConsumer` extracts the context, `HttpBackendGateway` injects it into the generated client calls.
- **Velocity** (`velocity/`): `HttpBackendGateway` injects the context into the generated `ApiClient` calls;
  `NatsSyncService` reads it from NATS headers and puts the trace id into its log lines. No exporter.
- **Portal** (`portal/server/lib/bff.ts`): `buildForwardHeaders` strips incoming trace headers and sets a new
  `traceparent` for the backend; a server span wraps each route.
- **Web** (`web/server/lib/proxy.ts`): the same as the portal, in the Nitro proxy.
- **Deployment** (`deploy/charts/blackhole/values.yaml`, `templates/backend-configmap.yaml`, `docs/kubernetes.md`):
  sampler variables, the Alloy tail-sampling note and the environment table.
- **Kubernetes-FLUX** (read only for this change): Alloy's OTLP receiver and Tempo are assumed as they are; the
  tail-sampling policy is a separate pull request in that repository, listed in `tasks.md` as a hand-off.
- **Dependencies (pinned here):** `io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0`
  `2.20.1-alpha` (`settings.gradle.kts`), `io.opentelemetry:opentelemetry-exporter-otlp` (version from the
  Micronaut platform BOM). Versions not listed here are not verified; see `design.md` D1.
