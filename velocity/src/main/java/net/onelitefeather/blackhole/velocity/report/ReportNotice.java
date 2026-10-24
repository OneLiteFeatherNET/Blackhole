package net.onelitefeather.blackhole.velocity.report;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What staff are told about a new report. It carries the player names and a short excerpt of the text, so
 * it exists only in the memory of the proxy that renders it: the names come from the proxy's online players
 * or the report, never from the event bus, and nothing is stored or sent to the backend.
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

    /** The longest excerpt that is shown. */
    public static final int EXCERPT_LENGTH = 80;

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
}
