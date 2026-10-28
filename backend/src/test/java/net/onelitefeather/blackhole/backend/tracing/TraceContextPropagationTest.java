package net.onelitefeather.blackhole.backend.tracing;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An inbound request that carries a W3C {@code traceparent} and {@code tracestate} must continue that trace in the
 * server span of the backend. The probe route reports the span the request ran in, while the request is still open.
 */
class TraceContextPropagationTest extends ApiIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    @Test
    void theServerSpanContinuesTheTraceOfTheTraceparentHeader() {
        assertEquals(TRACE_ID, probe(TRACEPARENT, "vendor=opaque")[0],
                "the server span must carry the trace id of the incoming traceparent");
    }

    @Test
    void theServerSpanIsAChildOfTheParentSpanOfTheTraceparentHeader() {
        assertEquals(PARENT_SPAN_ID, probe(TRACEPARENT, "vendor=opaque")[1],
                "the server span must be a child of the parent span id of the incoming traceparent");
    }

    @Test
    void theTracestateHeaderIsKeptOnTheServerSpan() {
        assertEquals("opaque", probe(TRACEPARENT, "vendor=opaque")[2],
                "the vendor entry of the incoming tracestate must reach the server span unchanged");
    }

    /** Returns the trace id, the parent span id and the vendor tracestate entry of the request's server span. */
    private String[] probe(String traceparent, String tracestate) {
        HttpRequest<?> request = HttpRequest.GET("/trace-probe")
                .header("traceparent", traceparent)
                .header("tracestate", tracestate);
        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            return client.toBlocking().retrieve(request).split(" ");
        }
    }
}
