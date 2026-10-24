package net.onelitefeather.blackhole.backend.casetimeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every class that writes a case event row must signal the live streams through {@link CaseChangeNotifier}. A new writer of
 * {@code case_event} that does not is a failing test, not a silent gap in the live updates.
 */
class CaseChangeSurfaceTest {

    private static final Path SOURCES = Path.of("src/main/java/net/onelitefeather/blackhole/backend");
    private static final Pattern WRITES_CASE_EVENTS = Pattern.compile("(INSERT INTO|UPDATE|DELETE FROM) case_event\\b");

    @Test
    void everyWriterOfCaseEventsHoldsTheNotifierAndTheSetIsWhatWeKnow() throws IOException {
        Set<String> writers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (WRITES_CASE_EVENTS.matcher(Files.readString(file)).find()) {
                    // Forward slashes on every platform, so the set compares equal on Windows too.
                    writers.add(SOURCES.relativize(file).toString().replace('\\', '/'));
                }
            }
        }

        assertEquals(Set.of(
                        "casetimeline/JdbcCaseEventRepository.java",
                        "casetimeline/JdbcCaseWriter.java",
                        "discord/ingest/JdbcDiscordIngestStore.java",
                        "discord/ticket/JdbcDiscordTicketStore.java",
                        "evidence/share/JdbcEvidenceShareStore.java"),
                writers, "a new writer of case_event rows needs a review of its live signal");
        for (String writer : writers) {
            String text = Files.readString(SOURCES.resolve(writer.replace('/', java.io.File.separatorChar)));
            assertTrue(text.contains("CaseChangeNotifier"), writer + " writes case events and must signal them");
        }
    }
}
