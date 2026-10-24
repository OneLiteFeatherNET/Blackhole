package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.evasion.dto.ClientFingerprintDTO;
import net.onelitefeather.blackhole.backend.evasion.service.ClientFingerprintService;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.FakeClientFingerprintStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFingerprintServiceTest {

    private static String owner(int n) {
        return String.format("%02x", n).repeat(64);
    }

    private final FakeClientFingerprintStore store = new FakeClientFingerprintStore();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final TestClock clock = new TestClock();
    private final ClientFingerprintService service = new ClientFingerprintService(
            store, new ClientFingerprintHasher(TestKeys.randomBytes()), events, clock, 14, 5);

    private static ClientFingerprintDTO.CreateRequest cheat(String owner) {
        return new ClientFingerprintDTO.CreateRequest(owner, "fabric", List.of("meteor-client:main", "minecraft:brand"), List.of());
    }

    private ClientFingerprintDTO.Response submit(ClientFingerprintDTO.CreateRequest request) {
        return assertInstanceOf(ClientFingerprintDTO.Response.class, service.record(request));
    }

    @Test
    void aSubmissionStoresOnlyTheKeyedHashAndTheOwner() {
        assertTrue(submit(cheat(owner(1))).stored());

        assertEquals(1, store.all().size());
        ClientFingerprintStore.Sighting row = store.all().getFirst();
        assertEquals(owner(1), row.owner());
        assertTrue(row.fingerprint().matches("[0-9a-f]{64}"), "a hash, not a trait");
        assertFalse(row.toString().contains("meteor"), "no trait in the row");
    }

    @Test
    void genericTraitsAreNotStoredAndAnswerStoredFalse() {
        assertFalse(submit(new ClientFingerprintDTO.CreateRequest(owner(1), "vanilla", List.of("minecraft:brand"), List.of())).stored());
        assertFalse(submit(new ClientFingerprintDTO.CreateRequest(owner(2), "fabric", null, null)).stored());

        assertTrue(store.all().isEmpty());
    }

    @Test
    void theSameOwnerSubmittingAgainIsOneRowAndNoMatch() {
        submit(cheat(owner(1)));
        clock.advance(Duration.ofMinutes(5));
        submit(cheat(owner(1)));

        assertEquals(1, store.all().size());
        assertTrue(events.events().isEmpty());
    }

    @Test
    void theOrderOfChannelsDoesNotChangeTheFingerprint() {
        submit(new ClientFingerprintDTO.CreateRequest(owner(1), "x", List.of("a:b", "c:d"), List.of()));
        submit(new ClientFingerprintDTO.CreateRequest(owner(2), "x", List.of("c:d", "a:b"), List.of()));

        assertEquals(store.all().get(0).fingerprint(), store.all().get(1).fingerprint());
    }

    @Test
    void theProtocolIsNotPartOfTheRequestSoOneClientIsOneFingerprintAcrossUpdates() {
        submit(cheat(owner(1)));
        submit(cheat(owner(2)));

        assertEquals(1, store.all().stream().map(ClientFingerprintStore.Sighting::fingerprint).distinct().count());
    }

    @Test
    void twoOwnersOnOneFingerprintPublishAMatchWithHashedOwnersAndNoFingerprint() {
        submit(cheat(owner(1)));
        submit(cheat(owner(2)));

        assertEquals(1, events.eventsOfType("evasion.client_match").size());
        Map<String, Object> payload = events.eventsOfType("evasion.client_match").getFirst().payload();
        assertEquals(List.of(owner(1), owner(2)), payload.get("owners"));
        assertEquals(java.util.Set.of("owners", "at"), payload.keySet(), "no fingerprint value, no trait");
    }

    @Test
    void aFingerprintOfFiveOwnersStillLinksAndOfSixLinksNobody() {
        for (int i = 1; i <= 5; i++) {
            submit(cheat(owner(i)));
        }
        assertEquals(4, service.linksOf(owner(1)).size(), "five owners link");
        int eventsAtFive = events.eventsOfType("evasion.client_match").size();

        submit(cheat(owner(6)));

        assertEquals(List.of(), service.linksOf(owner(1)), "six owners: a common fingerprint links nobody");
        assertEquals(eventsAtFive, events.eventsOfType("evasion.client_match").size(), "and no further match is announced");
    }

    @Test
    void linksListTheOtherOwnersMostRecentFirstWithTheNumberOfSharedFingerprints() {
        submit(cheat(owner(1)));
        clock.advance(Duration.ofHours(1));
        submit(cheat(owner(2)));
        ClientFingerprintDTO.CreateRequest second = new ClientFingerprintDTO.CreateRequest(owner(1), "x", List.of("other:channel"), List.of());
        submit(second);
        submit(new ClientFingerprintDTO.CreateRequest(owner(2), "x", List.of("other:channel"), List.of()));
        clock.advance(Duration.ofHours(1));
        submit(cheat(owner(3)));

        List<ClientFingerprintService.Link> links = service.linksOf(owner(1));

        assertEquals(List.of(owner(3), owner(2)), links.stream().map(ClientFingerprintService.Link::owner).toList());
        assertEquals(2, links.get(1).sharedFingerprints());
        assertEquals(1, links.get(0).sharedFingerprints());
    }

    @Test
    void aSightingOlderThanTheWindowLinksNobody() {
        submit(cheat(owner(1)));
        clock.advance(Duration.ofDays(14).plusSeconds(1));
        submit(cheat(owner(2)));

        assertEquals(List.of(), service.linksOf(owner(2)));
    }

    @Test
    void aSightingExactlyAtTheWindowEdgeStillLinks() {
        submit(cheat(owner(1)));
        clock.advance(Duration.ofDays(14));
        submit(cheat(owner(2)));

        assertEquals(1, service.linksOf(owner(2)).size());
    }

    @Test
    void oversizedTraitsAreRejectedAndStoreNothing() {
        List<String> tooMany = IntStream.range(0, 257).mapToObj(i -> "c:" + i).toList();

        ClientFingerprintDTO.Error error = assertInstanceOf(ClientFingerprintDTO.Error.class,
                service.record(new ClientFingerprintDTO.CreateRequest(owner(1), "x", tooMany, List.of())));

        assertEquals(ErrorKind.VALIDATION, error.kind());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void withoutAKeyTheServiceIsUnavailableAndStoresNothing() {
        ClientFingerprintService off = new ClientFingerprintService(store, new ClientFingerprintHasher((byte[]) null), events, clock, 14, 5);

        ClientFingerprintDTO.Error error = assertInstanceOf(ClientFingerprintDTO.Error.class, off.record(cheat(owner(1))));

        assertEquals(ErrorKind.UNAVAILABLE, error.kind());
        assertFalse(off.isEnabled());
        assertTrue(store.all().isEmpty());
        assertEquals(List.of(), off.linksOf(owner(1)));
    }
}
