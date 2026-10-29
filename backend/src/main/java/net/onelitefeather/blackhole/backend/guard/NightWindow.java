package net.onelitefeather.blackhole.backend.guard;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * A daily window in local time. The start is inclusive and the end exclusive. A window whose end is earlier than its
 * start crosses midnight, for example 23:00-02:00.
 */
public record NightWindow(LocalTime start, LocalTime end) {

    public NightWindow {
        if (start.equals(end)) {
            throw new IllegalArgumentException("the night window must not be empty: start and end are both " + start);
        }
    }

    /** Parses {@code HH:mm-HH:mm}. */
    public static NightWindow parse(String text) {
        String[] parts = text == null ? new String[0] : text.split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("the night window must look like 01:00-07:00: " + text);
        }
        try {
            return new NightWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("the night window must look like 01:00-07:00: " + text, e);
        }
    }

    public boolean contains(LocalTime time) {
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }
}
