package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PunishmentExpirySweeperIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "e1".repeat(64);

    @Test
    void theSweepRotatesAnExpiredMuteIntoHistoryAndLeavesTheRunningBan() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            PunishmentRepository punishments = context.getBean(PunishmentRepository.class);
            PunishmentProfileRepository profiles = context.getBean(PunishmentProfileRepository.class);
            long now = context.getBean(Clock.class).millis();

            PunishmentEntity expiredMute = punishments.save(punishment("expired-mute", "chat", now - 5_000));
            PunishmentEntity runningBan = punishments.save(punishment("running-ban", "network", now + 3_600_000));
            Map<String, PunishmentEntity> active = new HashMap<>();
            active.put("chat", expiredMute);
            active.put("network", runningBan);
            profiles.save(new PunishmentProfileEntity(OWNER, active, new ArrayList<>(), new HashMap<>()));

            context.getBean(PunishmentExpirySweeper.class).sweep();

            PunishmentProfileEntity swept = profiles.findById(OWNER).orElseThrow();
            assertFalse(swept.getActive().containsKey("chat"), "the expired mute left the chat slot");
            assertTrue(swept.getActive().containsKey("network"), "the running ban stays");
            assertEquals(1, swept.getHistory().size());
            assertEquals("expired-mute", swept.getHistory().getFirst().getIdentifier());
        }
    }

    private static PunishmentEntity punishment(String id, String section, long expiresAt) {
        Map<String, Object> metaData = new HashMap<>();
        metaData.put("creation_date", 1L);
        metaData.put("update_date", 1L);
        metaData.put("expiration_date", expiresAt);
        return new PunishmentEntity(id, UUID.randomUUID(), OWNER, section, false, 1L, null, metaData);
    }
}
