package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.evasion.IpCorrelationTokenEntity;
import net.onelitefeather.blackhole.backend.evasion.IpCorrelationTokenRepository;
import net.onelitefeather.blackhole.backend.evasion.service.IpCorrelationService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.intel.IpEnricher.IpEnrichment;
import net.onelitefeather.blackhole.backend.intel.dto.SessionDTO;
import net.onelitefeather.blackhole.backend.intel.names.NameHistoryService;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryNameHistoryStore;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.InMemorySessionStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport.Published;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session rules over in-memory collaborators: one fixture per test, a fixed clock, no network, no database. */
class SessionServiceTest {

    static final String OWNER = "a".repeat(128);
    static final String OTHER_OWNER = "b".repeat(128);
    static final String SALT = "test-salt";
    static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    static final String IP = "192.0.2.10";

    final TestClock clock = new TestClock();
    final InMemorySessionStore store = new InMemorySessionStore();
    final RecordingEventTransport transport = new RecordingEventTransport();
    final DomainEventPublisher events = new DomainEventPublisher(transport, clock);
    final List<String> lookedUp = new java.util.ArrayList<>();
    final IpEnricher enricher = ip -> {
        lookedUp.add(ip);
        return new IpEnrichment(64496L, "Example Transit", "DE");
    };
    final List<IpCorrelationTokenEntity> legacyRows = new java.util.ArrayList<>();
    final InMemoryRepository<IpCorrelationTokenRepository, IpCorrelationTokenEntity, UUID> legacy =
            InMemoryRepository.<IpCorrelationTokenRepository, IpCorrelationTokenEntity, UUID>of(
                            IpCorrelationTokenRepository.class, IpCorrelationTokenEntity::getIdentifier)
                    .finder("findByTokenAndLastSeenGreaterThanEquals", args -> legacyRows.stream()
                            .filter(e -> e.getToken().equals(args[0]) && e.getLastSeen() >= (long) args[1]).toList())
                    .build();

    final InMemoryNameHistoryStore names = new InMemoryNameHistoryStore();

    SessionService service(String salt) {
        IpCorrelationService correlation = new IpCorrelationService(legacy.repository(), store, events, salt, 7);
        return new SessionService(store, enricher, correlation, events, clock, new NameHistoryService(names, true), true);
    }

    static SessionDTO.StartRequest start(String sessionId, String owner, SessionOutcome outcome) {
        return new SessionDTO.StartRequest(sessionId, owner, PLAYER.toString(), "Steve", IP, 767, "play.example.net", outcome);
    }

    static String newSession() {
        return UUID.randomUUID().toString();
    }

    @Test
    void anAllowedLoginIsStoredWithTheClearIpTheRawUuidTheOwnerHashAndTheOutcome() {
        String sessionId = newSession();

        SessionDTO result = service(SALT).start(start(sessionId, OWNER, SessionOutcome.ALLOWED));

        SessionDTO.Response stored = assertInstanceOf(SessionDTO.Response.class, result);
        assertEquals(OWNER, stored.owner());
        assertEquals(PLAYER.toString(), stored.playerUuid());
        assertEquals(IP, stored.ip());
        assertEquals(SessionOutcome.ALLOWED, stored.outcome());
        assertNull(stored.endedAt(), "a start has no end yet");
    }

    @Test
    void aStoredLoginAddsItsNameToTheNameHistoryOnceAndARepeatAddsNothing() {
        String sessionId = newSession();
        SessionService service = service(SALT);

        service.start(start(sessionId, OWNER, SessionOutcome.ALLOWED));
        service.start(start(sessionId, OWNER, SessionOutcome.ALLOWED));

        assertEquals(1, names.all().size(), "one name for the one session and its repeat");
        assertEquals("Steve", names.all().getFirst().name());
        assertEquals(clock.millis(), names.all().getFirst().lastSeen(), "the name was seen at the login");
    }

    @Test
    void aDeniedLoginIsStoredWithTheDeniedOutcome() {
        SessionDTO result = service(SALT).start(start(newSession(), OWNER, SessionOutcome.DENIED));

        assertEquals(SessionOutcome.DENIED, assertInstanceOf(SessionDTO.Response.class, result).outcome());
    }

    @Test
    void aRepeatedStartWithTheSameSessionIdReturnsTheStoredRecordAndWritesNothingNew() {
        String sessionId = newSession();
        SessionService service = service(SALT);
        service.start(start(sessionId, OWNER, SessionOutcome.ALLOWED));

        SessionDTO repeat = service.start(start(sessionId, OWNER, SessionOutcome.DENIED));

        assertEquals(1, store.all().size(), "one row for one session id");
        assertEquals(SessionOutcome.ALLOWED, assertInstanceOf(SessionDTO.Response.class, repeat).outcome(), "the first answer stays");
        assertEquals(1, transport.published().stream().filter(p -> p.eventType().equals("session.started")).count(),
                "the start is announced once");
    }

    @Test
    void aMalformedOwnerIsAValidationErrorAndNothingIsStored() {
        SessionDTO result = service(SALT).start(start(newSession(), "not-a-hash", SessionOutcome.ALLOWED));

        SessionDTO.Error error = assertInstanceOf(SessionDTO.Error.class, result);
        assertEquals(ErrorKind.VALIDATION, error.kind());
        assertTrue(store.all().isEmpty(), "a rejected report writes nothing");
    }

    @Test
    void anIpThatIsNotAnAddressIsRejectedWithoutAnyLookup() {
        SessionDTO.StartRequest request = new SessionDTO.StartRequest(newSession(), OWNER, PLAYER.toString(), "Steve",
                "example.com", null, null, SessionOutcome.ALLOWED);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(SessionDTO.Error.class, service(SALT).start(request)).kind());
        assertTrue(lookedUp.isEmpty(), "a host name is never resolved");
    }

    @Test
    void theIpTokenIsStoredWhenTheSaltIsSet() {
        service(SALT).start(start(newSession(), OWNER, SessionOutcome.ALLOWED));

        assertTrue(store.all().getFirst().ipToken() != null && store.all().getFirst().ipToken().length() == 128,
                "a keyed token of 128 hex characters is stored");
    }

    @Test
    void theIpTokenIsNullAndTheSessionIsStoredWhenTheSaltIsUnset() {
        SessionDTO result = service("").start(start(newSession(), OWNER, SessionOutcome.ALLOWED));

        assertInstanceOf(SessionDTO.Response.class, result, "the session is still stored");
        assertNull(store.all().getFirst().ipToken(), "no token without a salt");
        assertEquals(IP, store.all().getFirst().ip(), "the clear IP is still stored");
    }

    @Test
    void theEnrichmentOfTheIpIsStoredWithTheSession() {
        service(SALT).start(start(newSession(), OWNER, SessionOutcome.ALLOWED));

        SessionRecord stored = store.all().getFirst();
        assertEquals(64496L, stored.asn());
        assertEquals("Example Transit", stored.asOrg());
        assertEquals("DE", stored.countryCode());
    }

    @Test
    void aDisconnectSetsTheEndOnceAndKeepsTheFirstEndTime() {
        String sessionId = newSession();
        SessionService service = service(SALT);
        service.start(start(sessionId, OWNER, SessionOutcome.ALLOWED));
        UUID id = UUID.fromString(sessionId);

        service.update(sessionId, new SessionDTO.UpdateRequest(null, null, true));
        long firstEnd = store.find(id).orElseThrow().endedAt();
        clock.advance(Duration.ofMinutes(5));
        service.update(sessionId, new SessionDTO.UpdateRequest(null, null, true));

        assertEquals(firstEnd, store.find(id).orElseThrow().endedAt(), "the first end time is kept");
        assertEquals(1, transport.published().stream().filter(p -> p.eventType().equals("session.ended")).count(),
                "the end is announced once");
    }

    @Test
    void theSessionEndedEventCarriesOnlyTheOwnerHashAndTheSessionId() {
        String sessionId = newSession();
        SessionService service = service(SALT);
        service.start(start(sessionId, OWNER, SessionOutcome.ALLOWED));

        service.update(sessionId, new SessionDTO.UpdateRequest(null, null, true));

        Published ended = transport.published().stream().filter(p -> p.eventType().equals("session.ended")).findFirst()
                .orElseThrow(() -> new AssertionError("a disconnect publishes session.ended"));
        assertEquals(Map.of("owner", OWNER, "sessionId", sessionId), ended.event().payload(),
                "no IP, UUID, name or token in a published event");
    }

    @Test
    void aBrandReportSetsTheBrandAndVersionOfTheSession() {
        String sessionId = newSession();
        SessionService service = service(SALT);
        service.start(start(sessionId, OWNER, SessionOutcome.ALLOWED));

        SessionDTO result = service.update(sessionId, new SessionDTO.UpdateRequest("vanilla", "1.21.4", false));

        SessionDTO.Response updated = assertInstanceOf(SessionDTO.Response.class, result);
        assertEquals("vanilla", updated.clientBrand());
        assertEquals("1.21.4", updated.clientVersion());
        assertNull(updated.endedAt(), "a brand report does not end the session");
    }

    @Test
    void anUpdateForAnUnknownSessionIsNotFoundAndWritesNothing() {
        SessionDTO result = service(SALT).update(newSession(), new SessionDTO.UpdateRequest("vanilla", null, true));

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(SessionDTO.Error.class, result).kind());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void theSessionStartedEventCarriesOnlyTheOwnerHashAndTheSessionId() {
        String sessionId = newSession();

        service(SALT).start(start(sessionId, OWNER, SessionOutcome.ALLOWED));

        Published published = transport.published().getFirst();
        assertEquals("session.started", published.eventType());
        assertEquals(Map.of("owner", OWNER, "sessionId", sessionId), published.event().payload(),
                "no IP, UUID, name or token in a published event");
    }

    @Test
    void aSessionReadBackIsNewestFirstAndPaged() {
        SessionService service = service(SALT);
        for (int minute = 0; minute < 3; minute++) {
            service.start(start(newSession(), OWNER, SessionOutcome.ALLOWED));
            clock.advance(Duration.ofMinutes(1));
        }

        SessionDTO.Page first = assertInstanceOf(SessionDTO.Page.class, service.listForOwner(OWNER, 0, 2));
        SessionDTO.Page second = assertInstanceOf(SessionDTO.Page.class, service.listForOwner(OWNER, 1, 2));

        assertEquals(2, first.sessions().size());
        assertTrue(first.hasMore(), "a third session is on the next page");
        assertEquals(1, second.sessions().size());
        assertFalse(second.hasMore());
        assertTrue(first.sessions().getFirst().startedAt() > first.sessions().getLast().startedAt(), "newest first");
    }

    @Test
    void aSessionReadForAnotherOwnerIsEmpty() {
        service(SALT).start(start(newSession(), OWNER, SessionOutcome.ALLOWED));

        SessionDTO.Page page = assertInstanceOf(SessionDTO.Page.class, service(SALT).listForOwner(OTHER_OWNER, null, null));

        assertTrue(page.sessions().isEmpty(), "the other owner has no sessions");
    }

    @Test
    void aSessionOfAnotherOwnerWithTheSameTokenRaisesOneEvasionEvent() {
        // The legacy table holds one owner on this IP; the new session is a second owner on the same token.
        SessionService service = service(SALT);
        String token = new IpCorrelationService(legacy.repository(), store, events, SALT, 7).tokenFor(IP).orElseThrow();
        IpCorrelationTokenEntity earlier = new IpCorrelationTokenEntity(token, OTHER_OWNER, clock.millis(), clock.millis(), 1,
                Map.of());
        legacyRows.add(earlier);

        service.start(start(newSession(), OWNER, SessionOutcome.ALLOWED));

        List<Published> evasion = transport.published().stream().filter(p -> p.eventType().equals("evasion.detected")).toList();
        assertEquals(1, evasion.size(), "one evasion event across the legacy and the new row");
        Collection<?> owners = (Collection<?>) evasion.getFirst().event().payload().get("owners");
        assertEquals(2, owners.size());
    }

    @Test
    void aSessionWithTheSaltUnsetRaisesNoEvasionEvent() {
        legacyRows.add(new IpCorrelationTokenEntity("unused", OTHER_OWNER, clock.millis(), clock.millis(), 1, Map.of()));

        service("").start(start(newSession(), OWNER, SessionOutcome.ALLOWED));

        assertTrue(transport.published().stream().noneMatch(p -> p.eventType().equals("evasion.detected")));
    }

    @Test
    void aSwitchedOffServiceAnswersNotFoundAndWritesNothing() {
        SessionService off = new SessionService(store, enricher, new IpCorrelationService(legacy.repository(), store, events, SALT, 7),
                events, clock, new NameHistoryService(names, false), false);

        SessionDTO started = off.start(start(newSession(), OWNER, SessionOutcome.ALLOWED));
        SessionDTO listed = off.listForOwner(OWNER, null, null);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(SessionDTO.Error.class, started).kind(), "start is off");
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(SessionDTO.Error.class, listed).kind(), "the read is off");
        assertTrue(store.all().isEmpty(), "nothing is stored while switched off");
        assertTrue(transport.published().isEmpty(), "nothing is published while switched off");
    }

    @Test
    void aPageNumberThatOverflowsTheOffsetIsAValidationErrorNotAServerError() {
        SessionDTO result = service(SALT).listForOwner(OWNER, Integer.MAX_VALUE, 50);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(SessionDTO.Error.class, result).kind());
    }
}
