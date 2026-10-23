package net.onelitefeather.blackhole.velocity.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * What staff are told about a new report. It carries the player names and a short excerpt of the text, so
 * it only ever travels over the Redis pub/sub channel (nothing is stored) and is never sent to the backend.
 *
 * @param reportId the backend's identifier of the report, null if the backend did not name one
 * @param reporter the reporting player's name
 * @param reported the reported player's name
 * @param category the backend category ({@code CHEATING}, ...)
 * @param excerpt  the first characters of the reporter's text, empty if there was none
 */
public record ReportNotice(
        @Nullable String reportId, @NotNull String reporter, @NotNull String reported, @NotNull String category, @NotNull String excerpt
) {

    /** The longest excerpt that is sent. */
    public static final int EXCERPT_LENGTH = 80;

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * @param details the reporter's text, may be null
     * @return the text on one line, shortened to {@link #EXCERPT_LENGTH} characters with an ellipsis
     */
    public static @NotNull String excerptOf(@Nullable String details) {
        if (details == null) {
            return "";
        }
        String line = details.replaceAll("\\s+", " ").strip();
        return line.length() <= EXCERPT_LENGTH ? line : line.substring(0, EXCERPT_LENGTH - 1).stripTrailing() + "…";
    }

    /** @return the wire form */
    public static @NotNull String encode(@NotNull ReportNotice notice) {
        try {
            return MAPPER.writeValueAsString(notice);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A report notice could not be encoded", e);
        }
    }

    /** @return the notice, empty if the text is not one */
    public static @NotNull Optional<ReportNotice> decode(@Nullable String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            ReportNotice notice = MAPPER.readValue(json, ReportNotice.class);
            boolean complete = notice.reporter() != null && notice.reported() != null && notice.category() != null;
            return complete ? Optional.of(new ReportNotice(notice.reportId(), notice.reporter(), notice.reported(),
                    notice.category(), notice.excerpt() == null ? "" : notice.excerpt())) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
