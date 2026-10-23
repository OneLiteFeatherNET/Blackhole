package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivePunishmentExpirerTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final ActivePunishmentExpirer expirer = new ActivePunishmentExpirer(
            bed.profiles.repository(), bed.slots, bed.eloBed.sections, bed.eloBed.events, bed.profileCache, bed.eloBed.clock);

    private void givenMuteAndBan() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "grief", null, null), ACTOR);
        bed.eloBed.events.events().clear();
    }

    private PunishmentProfileEntity profile() {
        return bed.profiles.repository().findById(OWNER).orElseThrow();
    }

    @Test
    void anExpiredMuteIsRotatedIntoHistoryAndTheBanStaysActive() {
        givenMuteAndBan();
        bed.eloBed.clock.advance(Duration.ofHours(2));

        boolean changed = expirer.expire(profile());

        assertTrue(changed);
        assertFalse(profile().getActive().containsKey("chat"), "the mute left the chat slot");
        assertTrue(profile().getActive().containsKey("network"), "the ban is still running");
        assertEquals(1, profile().getHistory().size());
    }

    @Test
    void anExpiredMutePublishesTheExpiryForTheChatSectionSoTheChatKeyIsCleared() {
        givenMuteAndBan();
        bed.eloBed.clock.advance(Duration.ofHours(2));

        expirer.expire(profile());

        List<RecordingEventPublisher.Event> expired = bed.eloBed.events.eventsOfType("punishment.expired");
        assertEquals(1, expired.size(), "only the mute expired");
        assertEquals("chat", expired.getFirst().payload().get("section"));
        assertEquals("MUTE", expired.getFirst().payload().get("enforcement"));
        assertEquals(OWNER, expired.getFirst().payload().get("owner"));
    }

    @Test
    void nothingIsPublishedWhileEverythingStillRuns() {
        givenMuteAndBan();

        boolean changed = expirer.expire(profile());

        assertFalse(changed);
        assertTrue(bed.eloBed.events.eventsOfType("punishment.expired").isEmpty());
    }

    @Test
    void aPermanentPunishmentNeverExpires() {
        bed.elo.applyDelta(OWNER, "network", -800, net.onelitefeather.blackhole.backend.elo.EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        bed.eloBed.clock.advance(Duration.ofDays(5000));

        assertFalse(expirer.expire(profile()), "permanent punishments have no expiry");
        assertTrue(profile().getActive().containsKey("network"));
    }

    @Test
    void expiringEvictsTheCachedProfile() {
        givenMuteAndBan();
        bed.profileCache.put(OWNER, new PunishProfileDTO(OWNER, Map.of(), List.of(), Map.of()));
        bed.eloBed.clock.advance(Duration.ofHours(2));

        expirer.expire(profile());

        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.profile(OWNER)));
    }

    @Test
    void aCachedPunishmentPastItsExpiryCountsAsExpired() {
        givenMuteAndBan();
        PunishProfileDTO cached = profile().toDTO();
        bed.eloBed.clock.advance(Duration.ofHours(2));

        boolean anyExpired = cached.active().values().stream().anyMatch(p -> expirer.isExpired(p.metaData()));

        assertTrue(anyExpired, "the cached mute is past its expiry");
    }
}
