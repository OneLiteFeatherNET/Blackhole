package net.onelitefeather.blackhole.backend.tracing;

import io.micronaut.tracing.opentelemetry.OpenTelemetryBuilderCustomizer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import jakarta.inject.Singleton;

/** Puts the span attribute allow-list in front of whatever span exporter the configuration selects. */
@Singleton
public class OtelExporterCustomizer implements OpenTelemetryBuilderCustomizer {

    @Override
    public void configure(AutoConfiguredOpenTelemetrySdkBuilder builder) {
        builder.addSpanExporterCustomizer((exporter, config) -> new AttributeAllowListExporter(exporter));
    }
}
