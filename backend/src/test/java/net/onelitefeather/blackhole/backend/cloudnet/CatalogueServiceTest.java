package net.onelitefeather.blackhole.backend.cloudnet;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.cloudnet.dto.CatalogueDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueServiceTest {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T12:00:00Z");

        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }

    private InMemorySharedCache cache;
    private MutableClock clock;
    private CatalogueService service;

    @BeforeEach
    void setUp() {
        this.cache = new InMemorySharedCache();
        this.clock = new MutableClock();
        this.service = new CatalogueService(this.cache, this.clock, Duration.ofMinutes(15));
    }

    private static CatalogueDTO.ReportRequest report(String proxyId, List<CatalogueDTO.TaskEntry> tasks, List<String> groups, List<CatalogueDTO.ServiceEntry> services) {
        return new CatalogueDTO.ReportRequest(proxyId, tasks, groups, services);
    }

    private static CatalogueDTO.ReportRequest bedwars(String proxyId) {
        return report(proxyId,
                List.of(new CatalogueDTO.TaskEntry("BedWars", List.of("Game"))),
                List.of("Game"),
                List.of(new CatalogueDTO.ServiceEntry("BedWars-1", "BedWars", List.of("Game"), true)));
    }

    @Test
    void aReportIsStoredUnderItsProxyKey() {
        CatalogueDTO result = this.service.report(bedwars("Proxy-1"));

        assertEquals(new CatalogueDTO.Stored("Proxy-1", this.clock.instant()), result);
        assertTrue(this.cache.contains(CacheKeys.cloudnetCatalogue("Proxy-1")), "one key per proxy");
    }

    @Test
    void withoutAReportTheCatalogueIsNotAvailable() {
        CatalogueDTO.Response response = this.service.catalogue();

        assertFalse(response.available(), "nothing reported yet");
        assertEquals(List.of(), response.tasks());
    }

    @Test
    void aReportedSnapshotIsReadBack() {
        this.service.report(bedwars("Proxy-1"));

        CatalogueDTO.Response response = this.service.catalogue();

        assertTrue(response.available());
        assertEquals(List.of("Proxy-1"), response.proxies());
        assertEquals(List.of(new CatalogueDTO.TaskEntry("BedWars", List.of("Game"))), response.tasks());
        assertEquals(List.of("Game"), response.groups());
        assertEquals(List.of(new CatalogueDTO.MergedService("BedWars-1", "BedWars", true)), response.services());
    }

    @Test
    void severalProxiesAreMergedByName() {
        this.service.report(bedwars("Proxy-1"));
        this.service.report(report("Proxy-2",
                List.of(new CatalogueDTO.TaskEntry("BedWars", List.of("Minigame")), new CatalogueDTO.TaskEntry("Lobby", List.of())),
                List.of("Minigame"),
                List.of(new CatalogueDTO.ServiceEntry("BedWars-1", "BedWars", List.of(), false), new CatalogueDTO.ServiceEntry("Lobby-1", "Lobby", List.of(), true))));

        CatalogueDTO.Response response = this.service.catalogue();

        assertEquals(List.of("Proxy-1", "Proxy-2"), response.proxies());
        assertEquals(List.of(new CatalogueDTO.TaskEntry("BedWars", List.of("Game", "Minigame")), new CatalogueDTO.TaskEntry("Lobby", List.of())), response.tasks());
        assertEquals(List.of("Game", "Minigame"), response.groups());
        assertEquals(List.of(new CatalogueDTO.MergedService("BedWars-1", "BedWars", true), new CatalogueDTO.MergedService("Lobby-1", "Lobby", true)),
                response.services(), "a service is running if any proxy says so");
    }

    @Test
    void aNewReportReplacesTheProxysPreviousSnapshot() {
        this.service.report(bedwars("Proxy-1"));
        this.service.report(report("Proxy-1", List.of(), List.of(), List.of()));

        CatalogueDTO.Response response = this.service.catalogue();

        assertEquals(List.of(), response.tasks(), "snapshots are full, not deltas");
    }

    @Test
    void aSnapshotExpiresAfterTheTtl() {
        this.service.report(bedwars("Proxy-1"));
        this.clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertFalse(this.service.catalogue().available(), "a silent proxy's snapshot is dead after the ttl");
    }

    @Test
    void onlyTheExpiredProxyDropsOut() {
        this.service.report(bedwars("Proxy-1"));
        this.clock.advance(Duration.ofMinutes(10));
        this.service.report(bedwars("Proxy-2"));
        this.clock.advance(Duration.ofMinutes(6));

        assertEquals(List.of("Proxy-2"), this.service.catalogue().proxies());
    }

    @Test
    void aCacheOutageOnReadMeansNotAvailable() {
        this.service.report(bedwars("Proxy-1"));
        this.cache.goDown();

        assertFalse(this.service.catalogue().available(), "the editor falls back to free text");
    }

    @Test
    void aCacheOutageOnWriteIsUnavailableSoTheProxyRetries() {
        this.cache.goDown();

        CatalogueDTO.Error error = assertInstanceOf(CatalogueDTO.Error.class, this.service.report(bedwars("Proxy-1")));

        assertEquals(ErrorKind.UNAVAILABLE, error.kind());
    }

    @Test
    void aProxyIdWithGlobCharactersIsRejected() {
        CatalogueDTO.Error error = assertInstanceOf(CatalogueDTO.Error.class, this.service.report(bedwars("Proxy-*")));

        assertEquals(ErrorKind.VALIDATION, error.kind());
    }

    @Test
    void aNameOverSixtyFourCharactersIsRejected() {
        CatalogueDTO result = this.service.report(report("Proxy-1", List.of(new CatalogueDTO.TaskEntry("x".repeat(65), List.of())), List.of(), List.of()));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(CatalogueDTO.Error.class, result).kind());
    }

    @Test
    void moreThanFiveHundredTasksAreRejected() {
        List<CatalogueDTO.TaskEntry> tasks = new ArrayList<>();
        for (int i = 0; i <= CatalogueService.MAX_TASKS; i++) {
            tasks.add(new CatalogueDTO.TaskEntry("t" + i, List.of()));
        }

        CatalogueDTO result = this.service.report(report("Proxy-1", tasks, List.of(), List.of()));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(CatalogueDTO.Error.class, result).kind());
        assertFalse(this.cache.contains(CacheKeys.cloudnetCatalogue("Proxy-1")), "a rejected snapshot is not stored");
    }

    @Test
    void moreThanFiveThousandServicesAreRejected() {
        List<CatalogueDTO.ServiceEntry> services = new ArrayList<>();
        for (int i = 0; i <= CatalogueService.MAX_SERVICES; i++) {
            services.add(new CatalogueDTO.ServiceEntry("s-" + i, "t", List.of(), true));
        }

        CatalogueDTO result = this.service.report(report("Proxy-1", List.of(), List.of(), services));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(CatalogueDTO.Error.class, result).kind());
    }

    @Test
    void missingListsCountAsEmpty() {
        CatalogueDTO result = this.service.report(new CatalogueDTO.ReportRequest("Proxy-1", null, null, null));

        assertInstanceOf(CatalogueDTO.Stored.class, result);
    }

    @Test
    void aStoredSnapshotWithOmittedEmptyListsIsStillMerged() {
        this.cache.put(CacheKeys.cloudnetCatalogue("Proxy-1"), io.micronaut.core.type.Argument.of(CatalogueSnapshot.class),
                new CatalogueSnapshot("Proxy-1", this.clock.instant(), null, null, null), Duration.ofMinutes(15));

        CatalogueDTO.Response response = this.service.catalogue();

        assertTrue(response.available());
        assertEquals(List.of(), response.tasks());
    }
}
