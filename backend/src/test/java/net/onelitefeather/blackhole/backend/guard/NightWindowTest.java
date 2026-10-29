package net.onelitefeather.blackhole.backend.guard;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightWindowTest {

    private static final NightWindow NIGHT = NightWindow.parse("01:00-07:00");
    private static final NightWindow ACROSS_MIDNIGHT = NightWindow.parse("23:00-02:00");

    @Test
    void startOfTheWindowIsInside() {
        assertTrue(NIGHT.contains(LocalTime.of(1, 0)), "the start of the window is inclusive");
    }

    @Test
    void theEndOfTheWindowIsOutside() {
        assertFalse(NIGHT.contains(LocalTime.of(7, 0)), "the end of the window is exclusive, 07:00 is already day");
    }

    @Test
    void aTimeInsideTheWindowIsInside() {
        assertTrue(NIGHT.contains(LocalTime.of(3, 0)), "03:00 is inside 01:00-07:00");
    }

    @Test
    void aWindowThatCrossesMidnightContainsLateEvening() {
        assertTrue(ACROSS_MIDNIGHT.contains(LocalTime.of(23, 30)), "23:30 is inside 23:00-02:00");
    }

    @Test
    void aWindowThatCrossesMidnightContainsEarlyMorning() {
        assertTrue(ACROSS_MIDNIGHT.contains(LocalTime.of(1, 30)), "01:30 is inside 23:00-02:00");
    }

    @Test
    void aWindowThatCrossesMidnightExcludesTheMiddleOfTheDay() {
        assertFalse(ACROSS_MIDNIGHT.contains(LocalTime.of(12, 0)), "12:00 is outside 23:00-02:00");
    }

    @Test
    void anEmptyWindowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> NightWindow.parse("01:00-01:00"),
                "a window whose start equals its end is empty and must be refused");
    }
}
