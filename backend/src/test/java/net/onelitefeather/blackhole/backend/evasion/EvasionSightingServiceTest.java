package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.evasion.dto.EvasionSightingDTO;
import net.onelitefeather.blackhole.backend.evasion.service.EvasionSightingService;
import net.onelitefeather.blackhole.backend.evasion.service.IpCorrelationService;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvasionSightingServiceTest {

    private static final String ME = "a".repeat(128);
    private static final String ALT = "b".repeat(128);
    private static final String OTHER = "c".repeat(128);

    private final TestClock clock = new TestClock();
    private final InMemoryRepository<IpCorrelationTokenRepository, IpCorrelationTokenEntity, UUID> store =
            InMemoryRepository.<IpCorrelationTokenRepository, IpCorrelationTokenEntity, UUID>of(
                            IpCorrelationTokenRepository.class, IpCorrelationTokenEntity::getIdentifier)
                    .finder("findByOwnerHash", args -> sightings().stream()
                            .filter(entity -> entity.getOwnerHash().equals(args[0])).toList())
                    .finder("findByTokenInAndLastSeenGreaterThanEquals", args -> sightings().stream()
                            .filter(entity -> ((Collection<?>) args[0]).contains(entity.getToken()))
                            .filter(entity -> entity.getLastSeen() >= (long) args[1]).toList())
                    .build();
    private final List<IpCorrelationTokenEntity> rows = new java.util.ArrayList<>();

    private List<IpCorrelationTokenEntity> sightings() {
        return this.rows;
    }

    private EvasionSightingService service(String salt) {
        IpCorrelationService correlation = new IpCorrelationService(
                this.store.repository(), new RecordingEventPublisher(), salt, 7);
        return new EvasionSightingService(this.store.repository(), correlation, this.clock);
    }

    private void seen(String owner, String token, Duration firstAgo, Duration lastAgo) {
        long now = this.clock.millis();
        this.rows.add(new IpCorrelationTokenEntity(token, owner, now - firstAgo.toMillis(), now - lastAgo.toMillis(), 1, Map.of()));
    }

    private EvasionSightingDTO.Response respond(String owner) {
        return assertInstanceOf(EvasionSightingDTO.Response.class, service("salt").forOwner(owner));
    }

    @Test
    void anOwnerWithoutSightingsHasNoLinkedOwners() {
        EvasionSightingDTO.Response response = respond(ME);

        assertTrue(response.detectionEnabled());
        assertTrue(response.linkedOwners().isEmpty());
        assertFalse(response.truncated());
        assertEquals(7, response.windowDays());
    }

    @Test
    void ownersSharingAnAddressAreLinkedAndTheRequestedOwnerIsNot() {
        seen(ME, "t1", Duration.ofDays(2), Duration.ofDays(1));
        seen(ALT, "t1", Duration.ofDays(3), Duration.ofHours(5));
        seen(OTHER, "t2", Duration.ofDays(1), Duration.ofHours(1));

        EvasionSightingDTO.Response response = respond(ME);

        assertEquals(1, response.linkedOwners().size());
        EvasionSightingDTO.LinkedOwner alt = response.linkedOwners().getFirst();
        assertEquals(ALT, alt.owner());
        assertEquals("SHARED_IP", alt.reason());
        assertEquals(1, alt.sharedAddresses());
        assertEquals(this.clock.millis() - Duration.ofDays(3).toMillis(), alt.firstSeen());
        assertEquals(this.clock.millis() - Duration.ofHours(5).toMillis(), alt.lastSeen());
    }

    @Test
    void moreSharedAddressesRaiseTheCountAndTheMostRecentOwnerComesFirst() {
        seen(ME, "t1", Duration.ofDays(2), Duration.ofDays(1));
        seen(ME, "t2", Duration.ofDays(2), Duration.ofDays(1));
        seen(ALT, "t1", Duration.ofDays(3), Duration.ofDays(2));
        seen(ALT, "t2", Duration.ofDays(3), Duration.ofDays(2));
        seen(OTHER, "t1", Duration.ofDays(1), Duration.ofHours(1));

        List<EvasionSightingDTO.LinkedOwner> linked = respond(ME).linkedOwners();

        assertEquals(List.of(OTHER, ALT), linked.stream().map(EvasionSightingDTO.LinkedOwner::owner).toList());
        assertEquals(2, linked.get(1).sharedAddresses());
    }

    @Test
    void sightingsOutsideTheDetectionWindowAreIgnored() {
        seen(ME, "t1", Duration.ofDays(2), Duration.ofDays(1));
        seen(ALT, "t1", Duration.ofDays(30), Duration.ofDays(20));

        assertTrue(respond(ME).linkedOwners().isEmpty());
    }

    @Test
    void anOwnerWhoseOwnSightingsAreOutsideTheWindowHasNoLinks() {
        seen(ME, "t1", Duration.ofDays(30), Duration.ofDays(20));
        seen(ALT, "t1", Duration.ofDays(2), Duration.ofDays(1));

        assertTrue(respond(ME).linkedOwners().isEmpty());
    }

    @Test
    void theListIsCappedAndMarkedTruncated() {
        seen(ME, "t1", Duration.ofDays(2), Duration.ofDays(1));
        for (int i = 0; i < 51; i++) {
            seen(String.format("%0128x", i + 1), "t1", Duration.ofDays(2), Duration.ofHours(i + 1));
        }

        EvasionSightingDTO.Response response = respond(ME);

        assertEquals(50, response.linkedOwners().size());
        assertTrue(response.truncated());
    }

    @Test
    void withoutASaltDetectionIsReportedAsDisabledAndNothingIsListed() {
        seen(ME, "t1", Duration.ofDays(2), Duration.ofDays(1));
        seen(ALT, "t1", Duration.ofDays(2), Duration.ofDays(1));

        EvasionSightingDTO.Response response = assertInstanceOf(
                EvasionSightingDTO.Response.class, service("").forOwner(ME));

        assertFalse(response.detectionEnabled());
        assertTrue(response.linkedOwners().isEmpty());
    }

    @Test
    void aMalformedOwnerIsAValidationError() {
        EvasionSightingDTO result = service("salt").forOwner("not-a-hash");

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(EvasionSightingDTO.Error.class, result).kind());
    }
}
