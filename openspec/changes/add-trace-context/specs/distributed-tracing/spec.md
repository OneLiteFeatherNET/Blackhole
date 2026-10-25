# Spec Delta

## Purpose

Defines how one Blackhole request is traced across the browser proxies, the backend, the NATS event bus, the Discord bot and Velocity: W3C Trace Context propagation, sampling, the span attribute allow-list and the log correlation. It holds no personal data.

## ADDED Requirements

### Requirement: Trace context uses W3C headers only
Every service SHALL propagate trace context with the W3C `traceparent` and `tracestate` headers. A service SHALL NOT set or read the W3C `baggage` header.

#### Scenario: Backend request carries the context
- **WHEN** the backend receives an HTTP request with a valid `traceparent`
- **THEN** its server span is a child of the caller's span with the same trace id

#### Scenario: Baggage is ignored
- **WHEN** a request carries a `baggage` header
- **THEN** no service copies it into a span, a log line or an outgoing request

### Requirement: The web and portal proxies start the trace and drop browser trace headers
The web Nitro proxy and the portal backend-for-frontend SHALL start a server span for each request and SHALL send their own `traceparent` to the backend. They SHALL remove any incoming `traceparent`, `tracestate` and `baggage` header before forwarding.

#### Scenario: Browser supplies a trace id
- **WHEN** a browser request carries a `traceparent` header
- **THEN** the backend receives a `traceparent` whose trace id was created by the proxy, not the browser's value

#### Scenario: No header from the browser
- **WHEN** a browser request carries no trace header
- **THEN** the proxy creates a new trace and forwards its `traceparent`

### Requirement: NATS messages carry the context in headers
The backend SHALL write `traceparent` and `tracestate` into the headers of every domain event message it publishes, and SHALL leave the event envelope and the `Nats-Msg-Id` header unchanged. A consumer SHALL continue the trace from those headers, and a message without them SHALL start a new trace.

#### Scenario: Publish with a current span
- **WHEN** the backend publishes a `punishment.created` event inside a request span
- **THEN** the message on `blackhole.events.punishment.created` has a `traceparent` header whose trace id equals the request's trace id, and its body is the unchanged `DomainEvent` envelope

#### Scenario: Consumer continues the trace
- **WHEN** the Discord bot receives a message with a `traceparent` header
- **THEN** its consumer span has the trace id of that header as parent

#### Scenario: Message without headers
- **WHEN** a consumer receives a message without a `traceparent` header
- **THEN** it starts a new trace and processes the message normally

### Requirement: Velocity propagates but does not export
Velocity SHALL read the context from NATS messages and SHALL inject it into its backend calls. It SHALL NOT create or export spans in this capability.

#### Scenario: Backend call from Velocity
- **WHEN** Velocity calls the backend while handling a NATS message with a `traceparent` header
- **THEN** the backend request carries a `traceparent` with the same trace id

#### Scenario: No exporter on the proxy
- **WHEN** Velocity starts
- **THEN** no OTLP exporter is created

### Requirement: Sampling is explicit per environment
Production services SHALL use the parent-based always-on head sampler, so that the collector can apply tail sampling to complete traces. Local development SHALL export nothing unless `OTEL_TRACES_EXPORTER` is set to `otlp`. The sampler SHALL be configurable with `OTEL_TRACES_SAMPLER` and `OTEL_TRACES_SAMPLER_ARG`.

#### Scenario: Local start
- **WHEN** the backend starts without `OTEL_TRACES_EXPORTER`
- **THEN** no span is exported

#### Scenario: Production start
- **WHEN** the backend starts with `OTEL_TRACES_EXPORTER=otlp` and no sampler variable
- **THEN** the sampler is `parentbased_always_on`

### Requirement: Span attributes are an allow-list
A span SHALL carry only the attributes of the allow-list in `design.md` D7. An attribute outside the allow-list SHALL be removed before export. Span attributes SHALL NOT contain Ban IDs, raw player UUIDs, IP addresses, Discord user ids, user agents, query strings, SQL parameter values or message content.

#### Scenario: Route template instead of path
- **WHEN** a request to a route with a player UUID in its path is traced
- **THEN** the `http.route` attribute holds the template and the span has no `url.path` attribute

#### Scenario: Scanned span set
- **WHEN** the test suite runs a request-driven scenario with an in-memory span exporter
- **THEN** no exported attribute value matches a Ban ID, UUID, IP address or Discord snowflake

### Requirement: Logs carry the trace identifiers
The backend and the Discord bot SHALL write `trace_id` and `span_id` into the JSON log lines of a request or a message that runs inside a span. Velocity SHALL write its trace id as a field of its log lines.

#### Scenario: Log line inside a request
- **WHEN** the backend logs a line while handling a traced request
- **THEN** the JSON line contains the request's `trace_id` and `span_id`

#### Scenario: Log line without a span
- **WHEN** a log line is written outside any span
- **THEN** the `trace_id` and `span_id` fields are absent or empty, and the line is still written
