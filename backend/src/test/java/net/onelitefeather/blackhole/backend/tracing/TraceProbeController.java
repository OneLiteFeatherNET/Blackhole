package net.onelitefeather.blackhole.backend.tracing;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.ReadableSpan;

/**
 * Test-only route. It reports the server span that the HTTP instrumentation opened for the request, so a
 * test can compare it with the headers it sent without waiting for the span to end.
 */
@Controller("/trace-probe")
@Secured(SecurityRule.IS_ANONYMOUS)
class TraceProbeController {

    @Get
    String probe() {
        Span span = Span.current();
        String parent = span instanceof ReadableSpan readable ? readable.getParentSpanContext().getSpanId() : "none";
        return span.getSpanContext().getTraceId() + " " + parent + " " + span.getSpanContext().getTraceState().get("vendor");
    }
}
