package net.onelitefeather.blackhole.backend.guard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The next night window in epoch milliseconds, for a fixed clock instant. Berlin is on CET (UTC+1) on these dates. */
class NightWindowScheduleTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static long at(String utc) {
        return Instant.parse(utc).toEpochMilli();
    }

    @Test
    void beforeTheWindowTonightTheWindowTonightIsTheNextOne() {
        // 00:30 Berlin on 30 October 2026
        Optional<NightWindowSchedule.Span> next = NightWindowSchedule.next(NightWindow.parse("01:00-07:00"), BERLIN,
                at("2026-10-29T23:30:00Z"));

        assertEquals(at("2026-10-30T00:00:00Z"), next.orElseThrow().startMillis(), "01:00 Berlin is 00:00 UTC");
        assertEquals(at("2026-10-30T06:00:00Z"), next.orElseThrow().endMillis(), "07:00 Berlin is 06:00 UTC");
    }

    @Test
    void anOpenWindowIsReturnedWithItsStartInThePast() {
        // 03:00 Berlin, inside the window
        Optional<NightWindowSchedule.Span> current = NightWindowSchedule.next(NightWindow.parse("01:00-07:00"), BERLIN,
                at("2026-10-30T02:00:00Z"));

        assertEquals(at("2026-10-30T00:00:00Z"), current.orElseThrow().startMillis(),
                "an open window must be returned with the start it began at, so the panel can say it is open");
    }

    @Test
    void aWindowCrossingMidnightStartsTonightWhenItIsStillDay() {
        // 22:00 Berlin
        Optional<NightWindowSchedule.Span> next = NightWindowSchedule.next(NightWindow.parse("23:00-02:00"), BERLIN,
                at("2026-10-30T21:00:00Z"));

        assertEquals(at("2026-10-30T22:00:00Z"), next.orElseThrow().startMillis(), "23:00 Berlin is 22:00 UTC");
        assertEquals(at("2026-10-31T01:00:00Z"), next.orElseThrow().endMillis(), "02:00 Berlin the next day is 01:00 UTC");
    }

    @Test
    void aWindowCrossingMidnightThatBeganTheDayBeforeIsOpenAfterMidnight() {
        // 01:00 Berlin on 31 October
        Optional<NightWindowSchedule.Span> open = NightWindowSchedule.next(NightWindow.parse("23:00-02:00"), BERLIN,
                at("2026-10-31T00:00:00Z"));

        assertEquals(at("2026-10-30T22:00:00Z"), open.orElseThrow().startMillis(),
                "at 01:00 the window that began at 23:00 the day before is still open");
    }

    @Test
    void afterTheWindowTheNextOneIsTheFollowingNight() {
        // 08:00 Berlin on 30 October
        Optional<NightWindowSchedule.Span> next = NightWindowSchedule.next(NightWindow.parse("01:00-07:00"), BERLIN,
                at("2026-10-30T07:00:00Z"));

        assertTrue(next.isPresent(), "a window that repeats daily always has a next occurrence");
        assertEquals(at("2026-10-31T00:00:00Z"), next.get().startMillis(), "the next window starts at 01:00 Berlin the following day");
    }
}
