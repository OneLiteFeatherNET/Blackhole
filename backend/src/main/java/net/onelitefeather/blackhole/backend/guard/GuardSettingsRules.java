package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bounds of the editable unattended settings. Pure: no state, no I/O. The service uses {@link #check} for a request,
 * and the seeding uses {@link #refusalOf} for the configured values, so both obey the same bounds.
 */
public final class GuardSettingsRules {

    static final int MIN_THRESHOLD_MINUTES = 5;
    static final int MAX_THRESHOLD_MINUTES = 240;
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    /** Zones that are region names in the runtime but are not a place that follows daylight saving time. */
    private static final Set<String> NOT_PLACES = Set.of("UTC", "GMT", "UT");
    private static final String LEVEL_MESSAGE = "unattendedLevel must be STRICT or LOCKDOWN; OFF is refused, because the unattended mode would do nothing";
    private static final String THRESHOLD_MESSAGE = "absenceThresholdMinutes must be a whole number of minutes from "
            + MIN_THRESHOLD_MINUTES + " to " + MAX_THRESHOLD_MINUTES;
    private static final String ENABLED_MESSAGE = "nightWindowEnabled is required";
    private static final String WINDOW_FORMAT_MESSAGE = "nightWindowStart and nightWindowEnd must be HH:mm times such as 01:00";
    private static final String WINDOW_EQUAL_MESSAGE = "nightWindowStart and nightWindowEnd must not be equal";
    private static final String ZONE_MESSAGE = "timeZone must be an IANA region id such as Europe/Berlin, not an offset such as +01:00 and not UTC";

    private GuardSettingsRules() {
    }

    /** The answer of {@link #check}: the settings to store, or the message of the first rule the request breaks. */
    public sealed interface Verdict {

        record Valid(UnattendedSettings settings) implements Verdict {
        }

        record Refused(String message) implements Verdict {
        }
    }

    /** Checks a request from the panel. The stored audit stamp is set by the caller, not here. */
    public static Verdict check(GuardSettingsDTO.UpdateRequest request) {
        String levelText = request.unattendedLevel() == null ? "" : request.unattendedLevel().trim().toUpperCase(Locale.ROOT);
        if (!levelText.equals("STRICT") && !levelText.equals("LOCKDOWN")) {
            return new Verdict.Refused(LEVEL_MESSAGE);
        }
        Integer minutes = request.absenceThresholdMinutes();
        if (minutes == null || minutes < MIN_THRESHOLD_MINUTES || minutes > MAX_THRESHOLD_MINUTES) {
            return new Verdict.Refused(THRESHOLD_MESSAGE);
        }
        if (request.nightWindowEnabled() == null) {
            return new Verdict.Refused(ENABLED_MESSAGE);
        }
        LocalTime start = timeOf(request.nightWindowStart());
        LocalTime end = timeOf(request.nightWindowEnd());
        if (start == null || end == null) {
            return new Verdict.Refused(WINDOW_FORMAT_MESSAGE);
        }
        if (start.equals(end)) {
            return new Verdict.Refused(WINDOW_EQUAL_MESSAGE);
        }
        if (request.timeZone() == null || !isPlace(request.timeZone().trim())) {
            return new Verdict.Refused(ZONE_MESSAGE);
        }
        UnattendedSettings candidate = new UnattendedSettings(GuardLevel.valueOf(levelText), Duration.ofMinutes(minutes),
                request.nightWindowEnabled(), new NightWindow(start, end), ZoneId.of(request.timeZone().trim()), 0L, null);
        return refusalOf(candidate)
                .<Verdict>map(Verdict.Refused::new)
                .orElseGet(() -> new Verdict.Valid(candidate));
    }

    /**
     * The first rule the values break, for the seeding of a configured row as well as for a request.
     *
     * @return the message, or empty when the values are within their bounds
     */
    public static Optional<String> refusalOf(UnattendedSettings settings) {
        if (settings.unattendedLevel() == GuardLevel.OFF) {
            return Optional.of(LEVEL_MESSAGE);
        }
        long nanos = settings.absenceThreshold().toNanos();
        long minutes = settings.absenceThreshold().toMinutes();
        if (nanos % Duration.ofMinutes(1).toNanos() != 0 || minutes < MIN_THRESHOLD_MINUTES || minutes > MAX_THRESHOLD_MINUTES) {
            return Optional.of(THRESHOLD_MESSAGE);
        }
        if (!isPlace(settings.zone().getId())) {
            return Optional.of(ZONE_MESSAGE);
        }
        return Optional.empty();
    }

    /** An IANA region id the runtime knows, that is not UTC, GMT or UT. Offsets such as +01:00 are not region ids. */
    static boolean isPlace(String zone) {
        return ZoneId.getAvailableZoneIds().contains(zone) && !NOT_PLACES.contains(zone);
    }

    /** A time written as HH:mm, or null when the text is not exactly that. */
    private static LocalTime timeOf(String text) {
        if (text == null || !HH_MM.matcher(text).matches()) {
            return null;
        }
        return LocalTime.parse(text);
    }
}
