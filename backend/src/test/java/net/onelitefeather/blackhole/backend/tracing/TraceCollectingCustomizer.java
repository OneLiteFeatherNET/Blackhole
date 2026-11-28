package net.onelitefeather.blackhole.backend.tracing;

import io.micronaut.tracing.opentelemetry.OpenTelemetryBuilderCustomizer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Test-only: sends the spans of this application context through the production attribute allow-list into an
 * in-memory exporter. One instance per context, so tests stay independent of each other.
 */
@Singleton
public class TraceCollectingCustomizer implements OpenTelemetryBuilderCustomizer {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final Semaphore serverSpansEnded = new Semaphore(0);

    @Override
    public void configure(AutoConfiguredOpenTelemetrySdkBuilder builder) {
        builder.addTracerProviderCustomizer((providerBuilder, config) -> providerBuilder.addSpanProcessor(
                SimpleSpanProcessor.create(new AttributeAllowListExporter(new ServerSpanCounter(this.exporter)))));
    }

    public InMemorySpanExporter exporter() {
        return this.exporter;
    }

    /**
     * Blocks until {@code count} server spans have ended, or fails after a bound. The span of a request ends after
     * the response is written, so a test that has the response in hand waits for the span on this event instead.
     */
    public void awaitServerSpans(int count) throws InterruptedException {
        if (!this.serverSpansEnded.tryAcquire(count, 10, TimeUnit.SECONDS)) {
            throw new AssertionError("expected " + count + " server spans to end within 10 seconds");
        }
    }

    /** Hands spans on unchanged and counts the server spans as they end. */
    private final class ServerSpanCounter implements SpanExporter {

        private final SpanExporter delegate;

        ServerSpanCounter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            spans.stream().filter(span -> span.getKind() == SpanKind.SERVER)
                    .forEach(span -> TraceCollectingCustomizer.this.serverSpansEnded.release());
            return this.delegate.export(spans);
        }

        @Override
        public CompletableResultCode flush() {
            return this.delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return this.delegate.shutdown();
        }
    }
}
