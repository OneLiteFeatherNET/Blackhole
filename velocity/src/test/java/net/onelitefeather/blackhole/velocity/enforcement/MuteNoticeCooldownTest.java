package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteNoticeCooldownTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private final MuteNoticeCooldown cooldown = new MuteNoticeCooldown(clock, COOLDOWN);

    @Test
    void theFirstAttemptGetsTheFullNotice() {
        assertTrue(cooldown.fullNoticeDue("alice"), "nothing was shown yet");
    }

    @Test
    void anAttemptWithinTheCooldownGetsTheReminder() {
        cooldown.fullNoticeDue("alice");
        clock.advance(Duration.ofSeconds(29));

        assertFalse(cooldown.fullNoticeDue("alice"), "the full notice was shown 29 seconds ago");
    }

    @Test
    void anAttemptAfterTheCooldownGetsTheFullNoticeAgain() {
        cooldown.fullNoticeDue("alice");
        clock.advance(COOLDOWN);

        assertTrue(cooldown.fullNoticeDue("alice"), "the cooldown is over");
    }

    @Test
    void aReminderDoesNotRestartTheCooldown() {
        cooldown.fullNoticeDue("alice");
        clock.advance(Duration.ofSeconds(20));
        cooldown.fullNoticeDue("alice");
        clock.advance(Duration.ofSeconds(10));

        assertTrue(cooldown.fullNoticeDue("alice"), "30 seconds passed since the full notice, not since the reminder");
    }

    @Test
    void eachPlayerHasTheirOwnCooldown() {
        cooldown.fullNoticeDue("alice");

        assertTrue(cooldown.fullNoticeDue("bob"), "bob has not been told yet");
    }
}
