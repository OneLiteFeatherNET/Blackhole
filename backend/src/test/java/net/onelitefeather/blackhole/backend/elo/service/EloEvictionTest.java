package net.onelitefeather.blackhole.backend.elo.service;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.elo.EloEventRepository;
import net.onelitefeather.blackhole.backend.elo.EloProfileCache;
import net.onelitefeather.blackhole.backend.elo.EloProfileRepository;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloTrack;
import net.onelitefeather.blackhole.backend.elo.dto.EloProfileDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateCache;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.Stubs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloEvictionTest {

    private static final String OWNER = "cd".repeat(64);

    @Test
    void anEloDeltaEvictsTheCachedProfile() {
        InMemorySharedCache sharedCache = new InMemorySharedCache();
        EloProfileCache eloProfileCache = new EloProfileCache(sharedCache, Duration.ofSeconds(30));
        eloProfileCache.put(OWNER, new EloProfileDTO(OWNER, 1000, 1000, 0L, 0L, Map.of()));
        assertTrue(sharedCache.contains(CacheKeys.elo(OWNER)));

        EloService service = new EloService(
                Stubs.repository(EloProfileRepository.class, Map.of()),
                Stubs.repository(EloEventRepository.class, Map.of()),
                Stubs.repository(PunishmentTemplateRepository.class, Map.of()),
                null,
                new RecordingEventPublisher(),
                eloProfileCache,
                new PunishmentTemplateCache(sharedCache, Duration.ofMinutes(5)),
                1000, 300, "", "", 700, 10, 86_400_000L, "PT24H", "P3D"
        );

        service.applyDelta(OWNER, EloTrack.CHAT, -50, EloReasonCode.TOXICITY_FLAG, null, Map.of());

        assertFalse(sharedCache.contains(CacheKeys.elo(OWNER)));
    }
}
