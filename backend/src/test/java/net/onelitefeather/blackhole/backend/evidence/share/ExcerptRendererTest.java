package net.onelitefeather.blackhole.backend.evidence.share;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExcerptRendererTest {

    private static Map<String, Object> line(Object timestamp, String speaker, String text) {
        Map<String, Object> line = new java.util.HashMap<>();
        line.put("timestamp", timestamp);
        line.put("speaker", speaker);
        line.put("text", text);
        return line;
    }

    private final List<Map<String, Object>> capture = List.of(
            line(1_000_000L, "Alice", "hello"),
            line(1_005_000L, "Bob", "hi"),
            line(1_012_500L, "Carol", "hey"),
            line(1_020_000L, "Alice", "bye"),
            line(1_030_000L, "Bob", "later"));

    @Test
    void theAppellantIsYouAndTheOthersAreNumberedByFirstAppearance() {
        List<ExcerptRenderer.Line> lines = ExcerptRenderer.render(this.capture, List.of(0, 1, 2, 3, 4), "Alice");

        assertEquals(List.of("You", "Player 2", "Player 3", "You", "Player 2"), lines.stream().map(ExcerptRenderer.Line::speaker).toList());
    }

    @Test
    void noOriginalLabelSurvives() {
        List<ExcerptRenderer.Line> lines = ExcerptRenderer.render(this.capture, List.of(0, 1, 2), "Alice");

        String all = lines.toString();
        assertFalse(all.contains("Alice") || all.contains("Bob") || all.contains("Carol"), "relabelled: " + all);
    }

    @Test
    void numberingFollowsTheSelectionNotTheCapture() {
        List<ExcerptRenderer.Line> lines = ExcerptRenderer.render(this.capture, List.of(2, 1, 0), "Alice");

        assertEquals("Player 2", lines.get(0).speaker(), "Carol comes first in this selection");
        assertEquals("Player 3", lines.get(1).speaker());
        assertEquals("You", lines.get(2).speaker());
    }

    @Test
    void timesAreOffsetsFromTheFirstSelectedLine() {
        List<ExcerptRenderer.Line> lines = ExcerptRenderer.render(this.capture, List.of(1, 2, 4), "Alice");

        assertEquals(List.of(0L, 7L, 25L), lines.stream().map(ExcerptRenderer.Line::offsetSeconds).toList());
    }

    @Test
    void isoTimestampsWork() {
        List<Map<String, Object>> iso = List.of(line("2030-01-01T00:00:00Z", "A", "x"), line("2030-01-01T00:01:30Z", "B", "y"));

        assertEquals(90L, ExcerptRenderer.render(iso, List.of(0, 1), "A").get(1).offsetSeconds());
    }

    @Test
    void anUnreadableTimestampHasNoOffset() {
        List<Map<String, Object>> broken = List.of(line("yesterday", "A", "x"));

        assertNull(ExcerptRenderer.render(broken, List.of(0), "A").getFirst().offsetSeconds());
    }

    @Test
    void aLineWithoutSpeakerIsAnotherPlayer() {
        List<Map<String, Object>> anonymous = new java.util.ArrayList<>(List.of(line(1L, "A", "x")));
        Map<String, Object> none = line(2L, "ignored", "y");
        none.remove("speaker");
        anonymous.add(none);

        assertEquals("Player 2", ExcerptRenderer.render(anonymous, List.of(0, 1), "A").get(1).speaker());
    }

    @Test
    void theSameInputGivesTheSameLines() {
        assertEquals(ExcerptRenderer.render(this.capture, List.of(0, 3), "Alice"), ExcerptRenderer.render(this.capture, List.of(0, 3), "Alice"));
    }
}
