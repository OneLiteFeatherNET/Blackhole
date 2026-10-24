package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.evasion.dto.ClientFingerprintDTO;
import net.onelitefeather.blackhole.backend.evasion.dto.EvasionSightingDTO;
import net.onelitefeather.blackhole.backend.evasion.service.ClientFingerprintService;
import net.onelitefeather.blackhole.backend.evasion.service.EvasionSightingService;
import net.onelitefeather.blackhole.backend.evasion.service.IpCorrelationService;
import net.onelitefeather.blackhole.backend.support.FakeClientFingerprintStore;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the IP and the client fingerprint signals combine in the sightings of an owner. */
class ClientFingerprintSightingsTest {

    private static final String ME = "a".repeat(128);
    private static final String ALT = "b".repeat(128);
    private static final String OTHER = "c".repeat(128);

    private final TestClock clock = new TestClock();
    private final List<IpCorrelationTokenEntity> rows = new ArrayList<>();
    private final InMemoryRepository<IpCorrelationTokenRepository, IpCorrelationTokenEntity, UUID> store =
            InMemoryRepository.<IpCorrelationTokenRepository, IpCorrelationTokenEntity, UUID>of(
                            IpCorrelationTokenRepository.class, IpCorrelationTokenEntity::getIdentifier)
                    .finder("findByOwnerHash", args -> rows.stream().filter(entity -> entity.getOwnerHash().equals(args[0])).toList())
                    .finder("findByTokenInAndLastSeenGreaterThanEquals", args -> rows.stream()
                            .filter(entity -> ((Collection<?>) args[0]).contains(entity.getToken()))
                            .filter(entity -> entity.getLastSeen() >= (long) args[1]).toList())
                    .build();
    private final ClientFingerprintService fingerprints = new ClientFingerprintService(
            new FakeClientFingerprintStore(), new ClientFingerprintHasher(TestKeys.randomBytes()), new RecordingEventPublisher(), clock, 14, 5);

    private EvasionSightingService service(String salt) {
        return new EvasionSightingService(store.repository(),
                new IpCorrelationService(store.repository(), new RecordingEventPublisher(), salt, 7), fingerprints, clock);
    }

    private void ipSeen(String owner, String token) {
        long now = clock.millis();
        rows.add(new IpCorrelationTokenEntity(token, owner, now - 1000, now, 1, Map.of()));
    }

    private void clientSeen(String owner) {
        fingerprints.record(new ClientFingerprintDTO.CreateRequest(owner, "x", List.of("meteor-client:main"), List.of()));
    }

    private EvasionSightingDTO.Response respond(String salt, String owner) {
        return assertInstanceOf(EvasionSightingDTO.Response.class, service(salt).forOwner(owner));
    }

    @Test
    void anIpOnlyLinkIsViaIp() {
        ipSeen(ME, "t1");
        ipSeen(ALT, "t1");

        EvasionSightingDTO.LinkedOwner link = respond("salt", ME).linkedOwners().getFirst();

        assertEquals(EvasionSightingDTO.Via.IP, link.via());
        assertEquals("SHARED_IP", link.reason());
        assertEquals(1, link.sharedAddresses());
    }

    @Test
    void aClientOnlyLinkIsViaClientAndTheWeakHint() {
        clientSeen(ME);
        clientSeen(ALT);

        EvasionSightingDTO.Response response = respond("salt", ME);

        assertEquals(1, response.linkedOwners().size());
        assertEquals(EvasionSightingDTO.Via.CLIENT, response.linkedOwners().getFirst().via());
        assertEquals("SHARED_CLIENT", response.linkedOwners().getFirst().reason());
        assertEquals(0, response.linkedOwners().getFirst().sharedAddresses());
        assertTrue(response.clientFingerprintEnabled());
        assertEquals(14, response.clientWindowDays());
    }

    @Test
    void anOwnerOnBothSignalsIsViaBothAndListedOnce() {
        ipSeen(ME, "t1");
        ipSeen(ALT, "t1");
        clientSeen(ME);
        clientSeen(ALT);
        clientSeen(OTHER);

        List<EvasionSightingDTO.LinkedOwner> linked = respond("salt", ME).linkedOwners();

        assertEquals(2, linked.size());
        assertEquals(EvasionSightingDTO.Via.BOTH, linked.stream().filter(l -> l.owner().equals(ALT)).findFirst().orElseThrow().via());
        assertEquals(EvasionSightingDTO.Via.CLIENT, linked.stream().filter(l -> l.owner().equals(OTHER)).findFirst().orElseThrow().via());
    }

    @Test
    void theClientSignalWorksWithoutTheIpSalt() {
        clientSeen(ME);
        clientSeen(ALT);

        EvasionSightingDTO.Response response = respond("", ME);

        assertFalse(response.detectionEnabled(), "the IP signal is off");
        assertEquals(1, response.linkedOwners().size());
    }

    @Test
    void withoutAFingerprintKeyTheClientSignalIsReportedOff() {
        EvasionSightingService off = new EvasionSightingService(store.repository(),
                new IpCorrelationService(store.repository(), new RecordingEventPublisher(), "salt", 7),
                new ClientFingerprintService(new FakeClientFingerprintStore(), new ClientFingerprintHasher((byte[]) null),
                        new RecordingEventPublisher(), clock, 14, 5), clock);

        EvasionSightingDTO.Response response = assertInstanceOf(EvasionSightingDTO.Response.class, off.forOwner(ME));

        assertFalse(response.clientFingerprintEnabled());
    }

    @Test
    void theClientWindowIsItsOwnAndLongerThanTheIpWindow() {
        clientSeen(ME);
        clock.advance(Duration.ofDays(10));
        clientSeen(ALT);

        assertEquals(1, respond("salt", ME).linkedOwners().size(), "10 days apart: outside the 7 day IP window, inside the 14 day client window");
    }
}
