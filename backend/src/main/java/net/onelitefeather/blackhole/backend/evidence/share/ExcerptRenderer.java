package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the lines of a chat capture the way the appealing player sees them. Pure: the same payload and selection always give the same
 * lines, and nothing is copied anywhere. The appellant's speaker is "You", every other speaker becomes "Player 2", "Player 3" in order
 * of first appearance in the selection whatever label the capture holds (so a legacy item with real names is covered too), times are
 * offsets in seconds from the first selected line, and the text is plain text.
 */
public final class ExcerptRenderer {

    public static final String APPELLANT_LABEL = "You";

    /**
     * One rendered line.
     *
     * @param speaker       "You", "Player 2", ...
     * @param offsetSeconds seconds after the first selected line, {@code null} when a timestamp cannot be read
     */
    public record Line(String speaker, @Nullable Long offsetSeconds, String text) {
    }

    /** The speaker label of a capture line as stored; a line without speaker counts as one anonymous speaker. */
    public static String speakerOf(Map<String, Object> line) {
        Object speaker = line.get("speaker");
        return speaker == null ? "" : speaker.toString();
    }

    /** The lines of the capture payload, empty when it has none. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> linesOf(@Nullable Map<String, Object> payload) {
        List<Map<String, Object>> found = new ArrayList<>();
        if (payload != null && payload.get("lines") instanceof List<?> list) {
            for (Object item : list) {
                found.add(item instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>());
            }
        }
        return found;
    }

    /**
     * @param lines     the capture lines
     * @param selection the chosen line indexes, ascending and distinct, each inside {@code lines}
     * @param appellant the speaker label that is the appealing player
     */
    public static List<Line> render(List<Map<String, Object>> lines, List<Integer> selection, String appellant) {
        Map<String, String> labels = new LinkedHashMap<>();
        int next = 2;
        Long first = null;
        List<Line> rendered = new ArrayList<>();
        for (int index : selection) {
            Map<String, Object> line = lines.get(index);
            String speaker = speakerOf(line);
            String label = labels.get(speaker);
            if (label == null) {
                if (speaker.equals(appellant)) {
                    label = APPELLANT_LABEL;
                } else {
                    label = "Player " + next++;
                }
                labels.put(speaker, label);
            }
            Long at = millis(line.get("timestamp"));
            if (first == null && at != null) {
                first = at;
            }
            Long offset = at == null || first == null ? null : Math.max(0L, (at - first) / 1000L);
            Object text = line.get("text");
            rendered.add(new Line(label, offset, text == null ? "" : text.toString()));
        }
        return List.copyOf(rendered);
    }

    private static @Nullable Long millis(@Nullable Object timestamp) {
        if (timestamp instanceof Number number) {
            return number.longValue();
        }
        if (timestamp instanceof String text) {
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private ExcerptRenderer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
