package net.onelitefeather.blackhole.backend.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Set;

/**
 * Drops every span attribute that is not on the allow-list before the span leaves the process, so that no
 * player identifier, IP address, raw URL or SQL parameter is exported. A {@code SpanProcessor} cannot remove
 * attributes, so this decorates the exporter: each span is handed to the delegate as a view whose attributes
 * are filtered; everything else is passed through unchanged.
 */
public final class AttributeAllowListExporter implements SpanExporter {

    static final Set<String> ALLOWED_ATTRIBUTE_KEYS = Set.of(
            "http.request.method",
            "http.response.status_code",
            "http.route",
            "url.template",
            "error.type",
            "messaging.system",
            "messaging.operation.type",
            "messaging.destination.name",
            "blackhole.event.type",
            "blackhole.component",
            "db.system",
            "db.operation.name");

    private final SpanExporter delegate;

    public AttributeAllowListExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return this.delegate.export(spans.stream().map(AttributeAllowListExporter::filtered).toList());
    }

    @Override
    public CompletableResultCode flush() {
        return this.delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return this.delegate.shutdown();
    }

    static SpanData filtered(SpanData span) {
        Attributes allowed = allowedOnly(span.getAttributes());
        // A view over the SDK's SpanData: every getter is delegated except getAttributes(), so the SDK's
        // own implementation (and any new getter it adds) stays as it is.
        return (SpanData) Proxy.newProxyInstance(
                SpanData.class.getClassLoader(),
                new Class<?>[]{SpanData.class},
                (proxy, method, args) -> "getAttributes".equals(method.getName())
                        ? allowed
                        : method.invoke(span, args));
    }

    static Attributes allowedOnly(Attributes attributes) {
        AttributesBuilder builder = Attributes.builder();
        attributes.forEach((key, value) -> {
            if (ALLOWED_ATTRIBUTE_KEYS.contains(key.getKey())) {
                put(builder, key, value);
            }
        });
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(AttributesBuilder builder, AttributeKey<?> key, Object value) {
        builder.put((AttributeKey<T>) key, (T) value);
    }
}
