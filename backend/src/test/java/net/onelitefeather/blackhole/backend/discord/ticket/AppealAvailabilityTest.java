package net.onelitefeather.blackhole.backend.discord.ticket;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a player may file: the date the Discord reply names for an appeal that is not possible yet. */
class AppealAvailabilityTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 100 * DAY;

    @Test
    void anOldEnoughPunishmentWithoutEarlierAppealsIsAvailableNow() {
        OptionalLong from = AppealAvailability.availableFrom(NOW, NOW - 20 * DAY, 14, List.of(), 7);

        assertTrue(from.isEmpty(), "nothing to wait for");
    }

    @Test
    void aPunishmentYoungerThanTheMinimumWaitsUntilItIsOldEnough() {
        OptionalLong from = AppealAvailability.availableFrom(NOW, NOW - 3 * DAY, 14, List.of(), 7);

        assertEquals(NOW + 11 * DAY, from.orElseThrow(), "creation plus fourteen days");
    }

    @Test
    void aDeniedAppealStartsTheCooldown() {
        OptionalLong from = AppealAvailability.availableFrom(NOW, NOW - 40 * DAY, 14, List.of(NOW - 2 * DAY), 7);

        assertEquals(NOW + 5 * DAY, from.orElseThrow(), "the denied appeal plus seven days");
    }

    @Test
    void theLaterOfTheMinimumAndTheCooldownCounts() {
        OptionalLong from = AppealAvailability.availableFrom(NOW, NOW - 10 * DAY, 14, List.of(NOW - 6 * DAY), 7);

        assertEquals(NOW + 4 * DAY, from.orElseThrow(), "the minimum (four more days) is later than the cooldown (one more day)");
    }

    @Test
    void aCooldownThatEndedDoesNotWait() {
        OptionalLong from = AppealAvailability.availableFrom(NOW, NOW - 40 * DAY, 14, List.of(NOW - 30 * DAY), 7);

        assertTrue(from.isEmpty());
    }

    @Test
    void theNewestPriorAppealDecidesTheCooldown() {
        OptionalLong from = AppealAvailability.availableFrom(NOW, NOW - 90 * DAY, 14, List.of(NOW - 30 * DAY, NOW - DAY), 7);

        assertEquals(NOW + 6 * DAY, from.orElseThrow());
    }
}
