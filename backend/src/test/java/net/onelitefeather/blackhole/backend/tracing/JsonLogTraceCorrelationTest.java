package net.onelitefeather.blackhole.backend.tracing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs a log call inside a span and checks, at append time, the {@code trace_id} and {@code span_id} that
 * the OpenTelemetry appender of logback.xml put into the MDC. The appender is found on the root logger and a
 * capturing appender is attached next to it for the duration of one test.
 */
class JsonLogTraceCorrelationTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("trace-correlation-test");
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
    private final MdcCapture capture = new MdcCapture();
    private AppenderAttachable<ILoggingEvent> otelAppender;

    @BeforeEach
    void attachCapture() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> otel = root.getAppender("OTEL");
        this.otelAppender = assertInstanceOf(AppenderAttachable.class, otel,
                "logback.xml must configure the OpenTelemetry appender as the root logger's OTEL appender");
        this.capture.start();
        this.otelAppender.addAppender(this.capture);
    }

    @AfterEach
    void detachCapture() {
        this.otelAppender.detachAppender(this.capture);
        this.capture.stop();
    }

    @Test
    void aLogLineInsideASpanCarriesTheSpanTraceId() {
        Span span = this.tracerProvider.get("log-test").spanBuilder("request").startSpan();
        try (var ignored = span.makeCurrent()) {
            this.logger.info("correlated line");
        } finally {
            span.end();
        }

        assertEquals(spanContext(span).getTraceId(), this.capture.single().get("trace_id"),
                "the log line must carry the trace id of the span it was written in");
    }

    @Test
    void aLogLineInsideASpanCarriesTheSpanId() {
        Span span = this.tracerProvider.get("log-test").spanBuilder("request").startSpan();
        try (var ignored = span.makeCurrent()) {
            this.logger.info("correlated line");
        } finally {
            span.end();
        }

        assertEquals(spanContext(span).getSpanId(), this.capture.single().get("span_id"),
                "the log line must carry the span id of the span it was written in");
    }

    @Test
    void aLogLineOutsideASpanCarriesNoTraceId() {
        this.logger.info("uncorrelated line");

        assertEquals(null, this.capture.single().get("trace_id"),
                "a log line written outside any span must not carry a trace id");
    }

    private static SpanContext spanContext(Span span) {
        SpanContext context = span.getSpanContext();
        assertNotNull(context, "the span must have a context");
        return context;
    }

    /**
     * Records the MDC map of each event. The OpenTelemetry appender writes trace_id and span_id into the
     * event's own MDC map (the JSON encoder renders that map), not into the thread's MDC.
     */
    private static final class MdcCapture extends AppenderBase<ILoggingEvent> {

        private final List<Map<String, String>> seen = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            this.seen.add(new HashMap<>(event.getMDCPropertyMap()));
        }

        Map<String, String> single() {
            assertEquals(1, this.seen.size(), "exactly one line is expected from the test's own log call");
            return this.seen.get(0);
        }
    }
}
