package net.onelitefeather.blackhole.backend.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeAllowListExporterTest {

    private final InMemorySpanExporter exported = InMemorySpanExporter.create();
    private final Tracer tracer = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new AttributeAllowListExporter(this.exported)))
            .build()
            .get("allow-list-test");

    @Test
    void anAllowedAttributeIsExported() {
        endSpanWith("http.route", "/punishments/{id}");

        assertTrue(attributeKeys().contains("http.route"), "allowed attribute must reach the exporter: " + attributeKeys());
    }

    @Test
    void theFullUrlAttributeIsRemovedBeforeExport() {
        endSpanWith("url.full", "https://example.test/punishments/1");

        assertFalse(attributeKeys().contains("url.full"), "url.full carries the path and query and must not be exported");
    }

    @Test
    void theClientAddressAttributeIsRemovedBeforeExport() {
        endSpanWith("client.address", "203.0.113.7");

        assertFalse(attributeKeys().contains("client.address"), "client.address is an IP address and must not be exported");
    }

    @Test
    void theDatabaseStatementAttributeIsRemovedBeforeExport() {
        endSpanWith("db.statement", "select * from punishments where id = ?");

        assertFalse(attributeKeys().contains("db.statement"), "db.statement carries bound parameters and must not be exported");
    }

    @Test
    void theEndUserIdAttributeIsRemovedBeforeExport() {
        endSpanWith("enduser.id", "player-uuid");

        assertFalse(attributeKeys().contains("enduser.id"), "enduser.id identifies a player and must not be exported");
    }

    @Test
    void theSpanNameIsKeptWhenAttributesAreFiltered() {
        endSpanWith("client.address", "203.0.113.7");

        assertEquals("span-under-test", onlySpan().getName(), "filtering attributes must not rename the span");
    }

    private void endSpanWith(String key, String value) {
        Span span = this.tracer.spanBuilder("span-under-test").startSpan();
        span.setAttribute(AttributeKey.stringKey(key), value);
        span.end();
    }

    private List<SpanData> spans() {
        return this.exported.getFinishedSpanItems();
    }

    private SpanData onlySpan() {
        assertEquals(1, spans().size(), "exactly one span is expected to be exported");
        return spans().get(0);
    }

    private List<String> attributeKeys() {
        return onlySpan().getAttributes().asMap().keySet().stream().map(AttributeKey::getKey).toList();
    }
}
