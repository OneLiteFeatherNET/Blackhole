package net.onelitefeather.blackhole.discord.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLayoutTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static LoggingEvent event(Level level, String message, Throwable error, Object... args) {
        Logger logger = new LoggerContext().getLogger("test.logger");
        return new LoggingEvent(Logger.class.getName(), logger, level, message, error, args);
    }

    private static JsonNode layout(LoggingEvent event) throws IOException {
        String line = new JsonLayout().doLayout(event);
        assertTrue(line.endsWith("\n"), "one line per event");
        assertEquals(1, line.strip().lines().count(), "never spans lines");
        return JSON.readTree(line);
    }

    @Test
    void a_line_is_json_with_time_level_logger_thread_and_message() throws IOException {
        JsonNode line = layout(event(Level.INFO, "Relayed {} messages", null, 3));

        assertEquals("INFO", line.get("level").asText(), "level");
        assertEquals("test.logger", line.get("logger").asText(), "logger");
        assertEquals("Relayed 3 messages", line.get("message").asText(), "formatted message");
        assertTrue(line.get("ts").asText().endsWith("Z"), "UTC timestamp");
        assertTrue(line.has("thread"), "thread");
    }

    @Test
    void quotes_and_line_breaks_in_a_message_are_escaped_so_a_line_stays_one_json_object() throws IOException {
        JsonNode line = layout(event(Level.WARN, "a \"quoted\"\nsecond line", null));

        assertEquals("a \"quoted\"\nsecond line", line.get("message").asText(), "round trips");
    }

    @Test
    void a_throwable_is_reduced_to_its_class_and_a_few_frames_without_its_own_message() throws IOException {
        IllegalStateException error = new IllegalStateException("text the library choked on: hunter2");

        JsonNode line = layout(event(Level.ERROR, "failed", error));

        assertEquals("java.lang.IllegalStateException", line.get("exception").asText(), "class");
        assertFalse(line.toString().contains("hunter2"), "the throwable's message is not logged");
        assertTrue(line.get("frames").size() <= 6, "at most six frames");
    }
}
