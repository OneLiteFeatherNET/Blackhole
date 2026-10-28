package net.onelitefeather.blackhole.backend.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the OpenTelemetry SDK from the same {@code otel.*} keys that application.yml sets and checks the
 * sampling decision it makes. Exporters are {@code none}, so nothing leaves the test. Each test owns its SDK.
 */
class OtelSamplerConfigTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

    private final Map<String, String> properties = new HashMap<>(Map.of(
            "otel.traces.exporter", "none",
            "otel.metrics.exporter", "none",
            "otel.logs.exporter", "none"));
    private AutoConfiguredOpenTelemetrySdk sdk;

    @AfterEach
    void closeSdk() {
        if (this.sdk != null) {
            this.sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    void aRootSpanIsSampledWhenNoSamplerIsConfigured() {
        assertTrue(rootSpanSampled(), "the default sampler must keep a root span");
    }

    @Test
    void aSampledRemoteParentIsContinuedUnderParentBasedAlwaysOn() {
        this.properties.put("otel.traces.sampler", "parentbased_always_on");

        assertTrue(childSampled(TraceFlags.getSampled()), "a sampled parent must be kept under parentbased_always_on");
    }

    @Test
    void anUnsampledRemoteParentIsRespectedUnderParentBasedAlwaysOn() {
        this.properties.put("otel.traces.sampler", "parentbased_always_on");

        assertFalse(childSampled(TraceFlags.getDefault()), "an unsampled parent must be respected under parentbased_always_on");
    }

    @Test
    void theSamplerArgumentIsReadWhenTheRatioSamplerIsChosen() {
        this.properties.put("otel.traces.sampler", "traceidratio");
        this.properties.put("otel.traces.sampler.arg", "0.0");

        assertFalse(rootSpanSampled(), "traceidratio with an argument of 0.0 must drop every root span");
    }

    private boolean rootSpanSampled() {
        Span span = tracer().spanBuilder("root").startSpan();
        try {
            return span.getSpanContext().isSampled();
        } finally {
            span.end();
        }
    }

    private boolean childSampled(TraceFlags parentFlags) {
        SpanContext remoteParent = SpanContext.createFromRemoteParent(
                TRACE_ID, PARENT_SPAN_ID, parentFlags, TraceState.getDefault());
        Context parent = Context.root().with(Span.wrap(remoteParent));
        Span span = tracer().spanBuilder("child").setParent(parent).startSpan();
        try {
            return span.getSpanContext().isSampled();
        } finally {
            span.end();
        }
    }

    private Tracer tracer() {
        this.sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.copyOf(this.properties))
                .disableShutdownHook()
                .build();
        return this.sdk.getOpenTelemetrySdk().getTracer("sampler-config-test");
    }
}
