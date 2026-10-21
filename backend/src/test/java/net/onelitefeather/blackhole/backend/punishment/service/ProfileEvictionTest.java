package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishType;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.Stubs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileEvictionTest {

    private static final String OWNER = "ab".repeat(64);

    private final InMemorySharedCache sharedCache = new InMemorySharedCache();
    private final ProfileCache profileCache = new ProfileCache(sharedCache, Duration.ofSeconds(30));

    private static PunishProfileDTO cachedProfile() {
        return new PunishProfileDTO(OWNER, Optional.empty(), Optional.empty(), List.of(), Map.of());
    }

    private static PunishmentTemplateEntity serverTemplate(UUID templateId) {
        return new PunishmentTemplateEntity(templateId, "Griefing", PunishType.SERVER, 0, new HashMap<>());
    }

    @Test
    void applyingATemplateEvictsTheCachedProfile() {
        UUID templateId = UUID.randomUUID();
        profileCache.put(OWNER, cachedProfile());
        PunishmentApplicationService service = new PunishmentApplicationService(
                Stubs.repository(PunishmentRepository.class, Map.of()),
                Stubs.repository(PunishmentProfileRepository.class, Map.of()),
                Stubs.repository(PunishmentTemplateRepository.class, Map.of(templateId, serverTemplate(templateId))),
                new RecordingEventPublisher(),
                profileCache,
                null
        );

        assertTrue(service.apply(OWNER, templateId, UUID.randomUUID()).isPresent());

        assertFalse(profileCache.get(OWNER).isPresent());
    }

    @Test
    void revokingABanEvictsTheCachedProfile() {
        UUID templateId = UUID.randomUUID();
        PunishmentEntity activeBan = new PunishmentEntity(
                "punishment-1", UUID.randomUUID(), PunishType.SERVER, null, serverTemplate(templateId), new HashMap<>()
        );
        PunishmentProfileEntity stored = new PunishmentProfileEntity(OWNER, null, activeBan, new ArrayList<>(), new HashMap<>());
        profileCache.put(OWNER, cachedProfile());
        PunishmentApplicationService service = new PunishmentApplicationService(
                Stubs.repository(PunishmentRepository.class, Map.of()),
                Stubs.repository(PunishmentProfileRepository.class, Map.of(OWNER, stored)),
                Stubs.repository(PunishmentTemplateRepository.class, Map.of()),
                new RecordingEventPublisher(),
                profileCache,
                null
        );

        assertTrue(service.revokeBan(OWNER, UUID.randomUUID()).isPresent());

        assertFalse(profileCache.get(OWNER).isPresent());
    }
}
