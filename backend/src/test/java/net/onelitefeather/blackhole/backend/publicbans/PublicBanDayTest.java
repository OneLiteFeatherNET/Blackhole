package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicBanDayTest {

    @Test
    void lastSecondOfTheUtcDayStaysInThatDay() {
        long millis = Instant.parse("2026-10-28T23:59:59Z").toEpochMilli();
        assertEquals(LocalDate.of(2026, 10, 28), PublicBanDay.toDate(PublicBanDay.of(millis)),
                "23:59:59 UTC belongs to the day it ends");
    }

    @Test
    void midnightUtcStartsTheNextDay() {
        long millis = Instant.parse("2026-10-29T00:00:00Z").toEpochMilli();
        assertEquals(LocalDate.of(2026, 10, 29), PublicBanDay.toDate(PublicBanDay.of(millis)),
                "00:00:00 UTC belongs to the day it starts");
    }
}
