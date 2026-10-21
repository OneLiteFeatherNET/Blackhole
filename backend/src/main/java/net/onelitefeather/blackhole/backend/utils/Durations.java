package net.onelitefeather.blackhole.backend.utils;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * ISO-8601 duration parsing shared by the section and template validation.
 */
public final class Durations {

    /**
     * Parses {@code raw} as an ISO-8601 duration ({@code PT24H}, {@code P3D}, ...).
     *
     * @return the duration, or empty if {@code raw} is blank, unparsable, zero or negative
     */
    public static Optional<Duration> parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Duration duration = Duration.parse(raw.trim());
            return duration.isZero() || duration.isNegative() ? Optional.empty() : Optional.of(duration);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private Durations() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
