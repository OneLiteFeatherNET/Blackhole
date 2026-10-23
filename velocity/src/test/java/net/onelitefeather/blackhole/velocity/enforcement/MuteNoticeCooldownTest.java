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
        assertTrue(cooldown.fullNoticeDue("alice", "mute-1"), "nothing was shown yet");
    }

    @Test
    void anAttemptWithinTheCooldownGetsTheReminder() {
        cooldown.fullNoticeDue("alice", "mute-1");
        clock.advance(Duration.ofSeconds(29));

        assertFalse(cooldown.fullNoticeDue("alice", "mute-1"), "the full notice was shown 29 seconds ago");
    }

    @Test
    void anAttemptAfterTheCooldownGetsTheFullNoticeAgain() {
        cooldown.fullNoticeDue("alice", "mute-1");
        clock.advance(COOLDOWN);

        assertTrue(cooldown.fullNoticeDue("alice", "mute-1"), "the cooldown is over");
    }

    @Test
    void aReminderDoesNotRestartTheCooldown() {
        cooldown.fullNoticeDue("alice", "mute-1");
        clock.advance(Duration.ofSeconds(20));
        cooldown.fullNoticeDue("alice", "mute-1");
        clock.advance(Duration.ofSeconds(10));

        assertTrue(cooldown.fullNoticeDue("alice", "mute-1"), "30 seconds passed since the full notice, not since the reminder");
    }

    @Test
    void eachPlayerHasTheirOwnCooldown() {
        cooldown.fullNoticeDue("alice", "mute-1");

        assertTrue(cooldown.fullNoticeDue("bob", "mute-1"), "bob has not been told yet");
    }

    @Test
    void aDifferentPunishmentOfTheSamePlayerGetsTheFullNoticeAtOnce() {
        cooldown.fullNoticeDue("alice", "mute-1");

        assertTrue(cooldown.fullNoticeDue("alice", "mute-2"), "a new mute is news to the player");
    }

    @Test
    void aForgottenPlayerIsTreatedAsNew() {
        cooldown.fullNoticeDue("alice", "mute-1");
        cooldown.forget("alice");

        assertTrue(cooldown.fullNoticeDue("alice", "mute-1"), "nothing is remembered after the player left");
    }
}
