package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.punishment.PunishType;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateCache;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateRequestDTO;
import net.onelitefeather.blackhole.backend.support.Stubs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TemplateEvictionTest {

    private final InMemorySharedCache sharedCache = new InMemorySharedCache();
    private final PunishmentTemplateCache templateCache = new PunishmentTemplateCache(sharedCache, Duration.ofMinutes(5));

    private PunishmentTemplateService service(Map<?, ?> templatesById) {
        return new PunishmentTemplateService(
                Stubs.repository(PunishmentTemplateRepository.class, templatesById),
                Stubs.repository(PunishmentRepository.class, Map.of()),
                templateCache
        );
    }

    private void primeCache(UUID id) {
        templateCache.put(id, new PunishTemplateDTO(Map.of(), "Old", PunishType.SERVER, 0, id));
        templateCache.putAll(List.of(new PunishTemplateDTO(Map.of(), "Old", PunishType.SERVER, 0, id)));
    }

    private void assertTemplateCacheCleared(UUID id) {
        assertFalse(sharedCache.contains(CacheKeys.template(id)));
        assertFalse(sharedCache.contains(CacheKeys.TEMPLATES_ALL));
    }

    @Test
    void updatingATemplateEvictsItAndTheList() {
        UUID id = UUID.randomUUID();
        PunishmentTemplateEntity existing = new PunishmentTemplateEntity(id, "Old", PunishType.SERVER, 0, new HashMap<>());
        primeCache(id);

        service(Map.of(id, existing)).update(new PunishTemplateRequestDTO(new HashMap<>(), "New", PunishType.SERVER, 0, id));

        assertTemplateCacheCleared(id);
    }

    @Test
    void removingATemplateEvictsItAndTheList() {
        UUID id = UUID.randomUUID();
        PunishmentTemplateEntity existing = new PunishmentTemplateEntity(id, "Old", PunishType.SERVER, 0, new HashMap<>());
        primeCache(id);

        service(Map.of(id, existing)).remove(id);

        assertTemplateCacheCleared(id);
    }

    @Test
    void creatingATemplateEvictsTheList() {
        UUID id = UUID.randomUUID();
        primeCache(id);

        service(Map.of()).create(new PunishTemplateRequestDTO(new HashMap<>(), "New", PunishType.SERVER, 0, null));

        assertFalse(sharedCache.contains(CacheKeys.TEMPLATES_ALL));
    }
}
