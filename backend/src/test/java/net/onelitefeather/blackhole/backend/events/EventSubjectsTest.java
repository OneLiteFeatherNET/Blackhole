package net.onelitefeather.blackhole.backend.events;

import net.onelitefeather.blackhole.backend.casetimeline.CaseEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSubjectsTest {

    /** Literal event types in the code: passed to publish(...), or held in a *_EVENT constant (a value with whitespace is SQL, not a type). */
    private static final Pattern PUBLISH_LITERAL = Pattern.compile("publish\\(\"([^\"]+)\"");
    private static final Pattern CONSTANT_LITERAL = Pattern.compile("\\b[A-Z_]+_EVENT\\s*=\\s*\"([^\"\\s]+)\"");

    @Test
    void anEventTypeBecomesASubjectUnderTheEventsPrefix() {
        assertEquals("blackhole.events.punishment.created", EventSubjects.forEventType("punishment.created"));
    }

    @Test
    void theStreamCapturesEverythingUnderThePrefix() {
        assertEquals("blackhole.events.>", EventSubjects.ALL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a b", "a\tb", "punishment.*", "punishment.>", "*", ">", "a..b", ".a", "a.", "a/b", "a$b",
            "punishment.created\n", "ünicode.type"})
    void aHostileEventTypeIsRejected(String eventType) {
        assertThrows(IllegalArgumentException.class, () -> EventSubjects.forEventType(eventType));
    }

    @Test
    void everyEventTypePublishedInTheCodeBaseYieldsALegalSubject() throws IOException {
        Set<String> types = publishedTypes();
        types.add(CaseEvents.routingKey("protocol.stored"));
        types.add(CaseEvents.routingKey("note.added"));
        types.add(CaseEvents.routingKey("ban_id.leak_marked"));

        assertTrue(types.size() >= 25, "the scan must find the published types, got " + types);
        for (String type : types) {
            String subject = EventSubjects.forEventType(type);
            assertEquals("blackhole.events." + type, subject);
            assertFalse(subject.contains("*") || subject.contains(">") || subject.contains(" "), subject);
        }
    }

    @Test
    void theSubjectCarriesNothingButTheEventType() {
        // The builder takes the type only: a payload value can never reach a subject.
        String subject = EventSubjects.forEventType("appeal.resolved");

        assertEquals("blackhole.events.appeal.resolved", subject);
    }

    private static Set<String> publishedTypes() throws IOException {
        Set<String> types = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (Pattern pattern : new Pattern[]{PUBLISH_LITERAL, CONSTANT_LITERAL}) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        types.add(matcher.group(1));
                    }
                }
            }
        }
        return types;
    }
}
