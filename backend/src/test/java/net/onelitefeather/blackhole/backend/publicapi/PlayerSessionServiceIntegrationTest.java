package net.onelitefeather.blackhole.backend.publicapi;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.JdbcCaseReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicSessionDTO;
import net.onelitefeather.blackhole.backend.publicapi.service.PlayerSessionService;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login, the session store and its expiry against a real PostgreSQL schema, on a clock the test moves by hand. */
@Tag("integration")
class PlayerSessionServiceIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "session-1";

    private PublicDatabase db;
    private TestClock clock;
    private SimpleMeterRegistry meters;
    private BanIdTestBed banIds;
    private PlayerSessionService service;
    private String banId;

    @BeforeEach
    void givenAMigratedSchemaAndAPunishmentWithABanId() throws NoSuchAlgorithmException {
        db = new PublicDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        clock = new TestClock();
        meters = new SimpleMeterRegistry();
        banIds = new BanIdTestBed(null);
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(42L);
        service = new PlayerSessionService(banIds.service, new JdbcCaseReader(db.dataSource), new JdbcPlayerSessionStore(db.dataSource),
                new PlayerSessionProperties(Duration.ofMinutes(30), Duration.ofHours(4), Duration.ofMinutes(1)), clock, meters, random);
        givenPunishment(ID);
        banId = banIds.issueFor(ID);
    }

    private void givenPunishment(String id) {
        db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb)",
                id, UUID.nameUUIDFromBytes("staff".getBytes()), OWNER, id.substring(0, 1).toUpperCase().repeat(8));
    }

    private void givenLeakedBanId() {
        db.execute("INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at, "
                + "ban_id_leaked_at, ban_id_leak_reason) VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, 1, 1, 5, 'SHARED')", ID);
    }

    private PublicSessionDTO.Created login() {
        return assertInstanceOf(PublicSessionDTO.Created.class, service.login(banId));
    }

    private Optional<PlayerSession> authenticate(String token) {
        return service.authenticate(token);
    }

    @Test
    void aGoodBanIdAnswersATokenACsrfValueTheAbsoluteExpiryAndFullScope() {
        PublicSessionDTO.Created created = login();

        assertTrue(created.token().startsWith("bhps_") && created.token().length() >= 48, "an opaque token of at least 256 bits: " + created.token().length());
        assertFalse(created.csrf().isBlank());
        assertEquals(clock.instant().plus(Duration.ofHours(4)).toString(), created.expiresAt());
        assertEquals(PlayerScope.FULL, created.scope());
    }

    @Test
    void theStoreHoldsTheHashOfTheTokenAndNeverTheTokenOrTheBanId() {
        PublicSessionDTO.Created created = login();

        String everything = String.join("|", db.rows("SELECT token_hash || '|' || punishment_identifier || '|' || scope || '|' || csrf FROM player_session"));
        assertFalse(everything.contains(created.token()), "no token in the table");
        assertFalse(everything.contains(created.token().substring(5)), "not even without the prefix");
        assertFalse(everything.contains(banId), "no Ban ID in the table");
        assertEquals(64L, db.count("SELECT length(token_hash) FROM player_session"), "a SHA-256 in hex");
        assertEquals(1, db.count("SELECT count(*) FROM player_session WHERE punishment_identifier = ?", ID));
    }

    @Test
    void aTokenIsAuthenticatedToItsOwnPunishmentOnly() {
        givenPunishment("other-punishment");
        String otherBanId = banIds.issueFor("other-punishment");
        PublicSessionDTO.Created mine = login();
        PublicSessionDTO.Created theirs = assertInstanceOf(PublicSessionDTO.Created.class, service.login(otherBanId));

        assertEquals(ID, authenticate(mine.token()).orElseThrow().punishmentIdentifier());
        assertEquals("other-punishment", authenticate(theirs.token()).orElseThrow().punishmentIdentifier());
    }

    @Test
    void everyFailedLoginIsTheSameAnswer() {
        List<String> wrong = Arrays.asList(null, "", "   ", "ABCD", "ZZZZZZZZ", "#ZZZZZZZZ", "U" + banId.substring(1),
                banId + "9", "A1B2C3D4", "<script>", "x".repeat(500));

        List<PublicSessionDTO> answers = wrong.stream().map(service::login).toList();

        for (PublicSessionDTO answer : answers) {
            PublicSessionDTO.Error error = assertInstanceOf(PublicSessionDTO.Error.class, answer);
            assertEquals(answers.getFirst(), error, "identical status, code and body for every failure");
            assertEquals("INVALID_BAN_ID", error.code());
            assertEquals(401, error.kind().status().getCode());
        }
    }

    @Test
    void theBanIdIsAcceptedHowAPlayerMayTypeIt() {
        assertInstanceOf(PublicSessionDTO.Created.class, service.login("#" + banId.toLowerCase()));
        assertInstanceOf(PublicSessionDTO.Created.class, service.login(" " + banId.substring(0, 4) + " " + banId.substring(4) + " "));
    }

    @Test
    void aLeakedBanIdLogsInWithLimitedScope() {
        givenLeakedBanId();

        PublicSessionDTO.Created created = login();

        assertEquals(PlayerScope.LIMITED, created.scope());
        assertEquals(PlayerScope.LIMITED, authenticate(created.token()).orElseThrow().scope());
    }

    @Test
    void aFullSessionBecomesLimitedWhenTheBanIdIsMarkedLeakedLater() {
        PublicSessionDTO.Created created = login();
        givenLeakedBanId();

        assertEquals(PlayerScope.LIMITED, authenticate(created.token()).orElseThrow().scope(), "never more than the case allows right now");
    }

    @Test
    void aSessionExpiresAfter30MinutesWithoutARequest() {
        PublicSessionDTO.Created created = login();

        clock.advance(Duration.ofMinutes(31));

        assertTrue(authenticate(created.token()).isEmpty());
    }

    @Test
    void aSessionIsStillGoodJustBeforeTheIdleLimit() {
        PublicSessionDTO.Created created = login();

        clock.advance(Duration.ofMinutes(29));

        assertTrue(authenticate(created.token()).isPresent());
    }

    @Test
    void activityExtendsTheSession() {
        PublicSessionDTO.Created created = login();

        clock.advance(Duration.ofMinutes(20));
        assertTrue(authenticate(created.token()).isPresent(), "after 20 minutes");
        clock.advance(Duration.ofMinutes(20));
        assertTrue(authenticate(created.token()).isPresent(), "after 40 minutes from login, 20 from the last request");
    }

    @Test
    void aSessionEndsAfterFourHoursEvenIfItWasUsedAMinuteAgo() {
        PublicSessionDTO.Created created = login();
        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofMinutes(29));
            assertTrue(authenticate(created.token()).isPresent(), "request " + i);
        }
        clock.advance(Duration.ofMinutes(7));
        assertTrue(authenticate(created.token()).isPresent(), "at 3h59, still inside the absolute limit");

        clock.advance(Duration.ofMinutes(2));

        assertTrue(authenticate(created.token()).isEmpty(), "past 4 hours since login, although used 2 minutes ago");
    }

    @Test
    void anUnknownTokenAndAnEmptyTokenAuthenticateNothing() {
        login();

        assertTrue(authenticate("bhps_" + "A".repeat(43)).isEmpty());
        assertTrue(authenticate("").isEmpty());
        assertTrue(authenticate(null).isEmpty());
    }

    @Test
    void logoutDeletesTheRowAndTheTokenStopsWorking() {
        PublicSessionDTO.Created created = login();
        PlayerSession session = authenticate(created.token()).orElseThrow();

        service.logout(session);

        assertEquals(0, db.count("SELECT count(*) FROM player_session"));
        assertTrue(authenticate(created.token()).isEmpty());
    }

    @Test
    void theSweeperDeletesIdleAndAbsolutelyExpiredRowsAndKeepsLiveOnes() {
        PublicSessionDTO.Created idle = login();
        clock.advance(Duration.ofMinutes(20));
        PublicSessionDTO.Created recent = login();
        clock.advance(Duration.ofMinutes(15));
        assertEquals(1, service.sweep(), "the first login is 35 minutes idle");
        assertTrue(authenticate(idle.token()).isEmpty());
        assertTrue(authenticate(recent.token()).isPresent());

        clock.advance(Duration.ofHours(5));

        assertEquals(1, service.sweep(), "the second is past its limits");
        assertEquals(0, db.count("SELECT count(*) FROM player_session"));
    }

    @Test
    void deletingThePunishmentDeletesItsSessions() {
        login();

        db.execute("DELETE FROM punishments WHERE identifier = ?", ID);

        assertEquals(0, db.count("SELECT count(*) FROM player_session"));
    }

    @Test
    void loginsAreCountedByResultWithNothingElseInTheTags() {
        login();
        service.login("ZZZZZZZZ");
        service.login("ZZZZZZZZ");
        givenLeakedBanId();
        login();

        assertEquals(1.0, meters.get("blackhole.public.login").tag("result", "success").counter().count());
        assertEquals(2.0, meters.get("blackhole.public.login").tag("result", "failure").counter().count());
        assertEquals(1.0, meters.get("blackhole.public.login").tag("result", "limited").counter().count());
        meters.getMeters().forEach(meter -> {
            assertFalse(meter.getId().toString().contains(banId), "no Ban ID in a meter");
            assertEquals(1, meter.getId().getTags().size(), "result is the only tag");
        });
    }
}
