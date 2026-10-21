package net.onelitefeather.blackhole.backend.section;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionServiceTest {

    private final InMemorySharedCache sharedCache = new InMemorySharedCache();
    private final SectionCache sectionCache = new SectionCache(sharedCache, Duration.ofMinutes(5));
    private final InMemoryRepository<SectionRepository, SectionEntity, String> repository =
            InMemoryRepository.<SectionRepository, SectionEntity, String>of(SectionRepository.class, SectionEntity::getSectionKey).build();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final List<String> notified = new ArrayList<>();
    private final SectionService service = new SectionService(repository.repository(), sectionCache, events, notified::add);

    private static SectionDTO.CreateRequest pvp() {
        return new SectionDTO.CreateRequest("pvp", "PvP", EnforcementKind.BAN, null, 1000, 700, "P3D", 300, 700, "banned", null);
    }

    private SectionDTO.Error expectError(SectionDTO result, ErrorKind kind) {
        SectionDTO.Error error = assertInstanceOf(SectionDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(kind, error.kind(), "error kind of: " + error.errorMessage());
        return error;
    }

    @Test
    void creatingASectionStoresItAndStartsEnabled() {
        SectionDTO result = service.create(pvp());

        SectionDTO.Response response = assertInstanceOf(SectionDTO.Response.class, result, "created section");
        assertEquals("pvp", response.key());
        assertTrue(response.enabled(), "a new section is enabled");
        assertEquals(10, response.decayPerDay(), "decay defaults when not supplied");
        assertEquals(1, repository.all().size(), "stored once");
    }

    @Test
    void creatingASectionPublishesTheChangeToEventsAndProxies() {
        service.create(pvp());

        assertEquals(1, events.eventsOfType("section.changed").size(), "section.changed domain event");
        assertEquals("pvp", events.eventsOfType("section.changed").getFirst().payload().get("section"), "event names the section");
        assertEquals(List.of("pvp"), notified, "proxies are notified");
    }

    @Test
    void creatingASectionWithAnExistingKeyIsAConflict() {
        service.create(pvp());

        expectError(service.create(pvp()), ErrorKind.CONFLICT);
        assertEquals(1, repository.all().size(), "no second section stored");
    }

    @Test
    void creatingASectionWithInvalidThresholdsStoresNothing() {
        SectionDTO.CreateRequest invalid = new SectionDTO.CreateRequest(
                "pvp", "PvP", EnforcementKind.BAN, null, 1000, null, null, 1200, 1500, "banned", null);

        expectError(service.create(invalid), ErrorKind.VALIDATION);
        assertEquals(0, repository.all().size(), "nothing stored");
        assertTrue(events.events().isEmpty(), "nothing published");
    }

    @Test
    void creatingAServerSectionWithoutPatternsIsRejected() {
        SectionDTO.CreateRequest invalid = new SectionDTO.CreateRequest(
                "bedwars", "Bedwars", EnforcementKind.SERVER, List.of(), 1000, 700, "P3D", 300, 700, "banned", null);

        expectError(service.create(invalid), ErrorKind.VALIDATION);
    }

    @Test
    void aCreatedServerSectionKeepsItsPatternsSorted() {
        SectionDTO.CreateRequest request = new SectionDTO.CreateRequest(
                "bedwars", "Bedwars", EnforcementKind.SERVER, List.of("bw-*", "bedwars-*"), 1000, 700, "P3D", 300, 700, "banned", null);

        SectionDTO.Response response = assertInstanceOf(SectionDTO.Response.class, service.create(request));

        assertEquals(List.of("bedwars-*", "bw-*"), response.servers(), "patterns are returned sorted");
    }

    @Test
    void updatingChangesTheFieldsAndKeepsTheKey() {
        service.create(pvp());
        SectionDTO.UpdateRequest update = new SectionDTO.UpdateRequest(
                "pvp", "PvP Arena", EnforcementKind.BAN, null, 1200, null, null, 400, 800, "gone", 5, true);

        SectionDTO.Response response = assertInstanceOf(SectionDTO.Response.class, service.update(update));

        assertEquals("PvP Arena", response.displayName());
        assertEquals(1200, response.baseScore());
        assertEquals(null, response.softThreshold(), "soft tier removed");
        assertEquals(1, repository.all().size(), "updated in place");
    }

    @Test
    void updatingAMissingSectionIsNotFound() {
        SectionDTO.UpdateRequest update = new SectionDTO.UpdateRequest(
                "ghost", "Ghost", EnforcementKind.BAN, null, 1000, null, null, 300, 700, "gone", 5, true);

        expectError(service.update(update), ErrorKind.NOT_FOUND);
    }

    @Test
    void updatingWithInvalidValuesLeavesTheSectionUnchanged() {
        service.create(pvp());
        SectionDTO.UpdateRequest update = new SectionDTO.UpdateRequest(
                "pvp", "PvP", EnforcementKind.BAN, null, 1000, null, null, 1000, 1100, "gone", 5, true);

        expectError(service.update(update), ErrorKind.VALIDATION);
        assertEquals(300, assertInstanceOf(SectionDTO.Response.class, service.get("pvp")).permanentThreshold(), "unchanged");
    }

    @Test
    void disablingASectionMarksItDisabled() {
        service.create(pvp());

        SectionDTO.Response response = assertInstanceOf(SectionDTO.Response.class, service.disable("pvp"));

        assertFalse(response.enabled(), "section is disabled");
    }

    @Test
    void disablingAMissingSectionIsNotFound() {
        expectError(service.disable("ghost"), ErrorKind.NOT_FOUND);
    }

    @Test
    void findServesTheSectionFromTheSharedCacheAfterTheFirstRead() {
        service.create(pvp());
        sharedCache.evict(CacheKeys.section("pvp"));

        service.find("pvp");

        assertTrue(sharedCache.contains(CacheKeys.section("pvp")), "a found section is cached");
    }

    @Test
    void aMissingSectionIsNotCached() {
        assertTrue(service.find("ghost").isEmpty(), "unknown section");
        assertFalse(sharedCache.contains(CacheKeys.section("ghost")), "misses are not cached");
    }

    @Test
    void writesEvictTheCachedSectionAndTheList() {
        service.create(pvp());
        service.list();
        service.find("pvp");
        assertTrue(sharedCache.contains(CacheKeys.SECTIONS_ALL) && sharedCache.contains(CacheKeys.section("pvp")), "cache warm");

        service.disable("pvp");

        assertFalse(sharedCache.contains(CacheKeys.SECTIONS_ALL), "list evicted");
        assertFalse(sharedCache.contains(CacheKeys.section("pvp")), "section evicted");
    }

    @Test
    void listReturnsEverySectionIncludingDisabledOnes() {
        service.create(pvp());
        service.disable("pvp");

        assertEquals(1, service.list().size(), "disabled sections are still listed");
    }
}
