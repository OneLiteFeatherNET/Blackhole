package net.onelitefeather.blackhole.backend.guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/** Works out when the night window next applies, in epoch milliseconds. Pure: the caller passes the instant. */
public final class NightWindowSchedule {

    private NightWindowSchedule() {
    }

    /**
     * @param startMillis when this occurrence of the window began or begins
     * @param endMillis   when it ends (exclusive)
     */
    public record Span(long startMillis, long endMillis) {
    }

    /**
     * The occurrence that is open at {@code nowMillis}, or else the next one. An open window is returned with its start in
     * the past, so the panel can say that it is open.
     */
    public static Optional<Span> next(NightWindow window, ZoneId zone, long nowMillis) {
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        boolean crossesMidnight = window.end().isBefore(window.start());
        for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
            LocalDate day = today.plusDays(dayOffset);
            ZonedDateTime start = day.atTime(window.start()).atZone(zone);
            LocalDate endDay = crossesMidnight ? day.plusDays(1) : day;
            ZonedDateTime end = endDay.atTime(window.end()).atZone(zone);
            if (end.toInstant().toEpochMilli() > nowMillis) {
                return Optional.of(new Span(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli()));
            }
        }
        return Optional.empty();
    }
}
