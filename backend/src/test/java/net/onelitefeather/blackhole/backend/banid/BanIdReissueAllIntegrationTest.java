package net.onelitefeather.blackhole.backend.banid;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import net.onelitefeather.blackhole.backend.casetimeline.JdbcCaseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code BanIdReissueAll} against a real schema: a key rotation reissues every Ban ID once, idempotently and resumably. */
@Tag("integration")
class BanIdReissueAllIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT_OF_P001 =
            " FROM case_event WHERE punishment_identifier = 'p001' AND type = 'ban_id.reissued'";

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final SnapshotLogAppender appender = new SnapshotLogAppender();
    private Level previousLevel;

    private CaseDatabase db;
    private JdbcBanIdStore store;
    private JdbcCaseWriter writer;
    private BanIdCrypto oldCrypto;
    private BanIdCrypto newCrypto;
    private BanIdService oldService;
    private BanIdService newService;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void givenAMigratedSchemaAndARotatedKey() {
        this.previousLevel = this.root.getLevel();
        this.root.setLevel(Level.ALL);
        this.appender.start();
        this.root.addAppender(this.appender);
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        store = new JdbcBanIdStore(db.dataSource);
        writer = new JdbcCaseWriter(db.dataSource);
        oldCrypto = new BanIdCrypto(randomKey());
        newCrypto = new BanIdCrypto(randomKey());
        oldService = new BanIdService(store, oldCrypto, new BanIdGenerator(new Random(1)), null);
        newService = new BanIdService(store, newCrypto, new BanIdGenerator(new Random(2)), null);
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void releaseLogs() {
        this.root.detachAppender(this.appender);
        this.root.setLevel(this.previousLevel);
    }

    private static BanIdProperties randomKey() {
        byte[] key = new byte[32];
        new Random().nextBytes(key);
        return new BanIdProperties(Base64.getEncoder().encodeToString(key));
    }

    private BanIdReissueAll job(CaseChanges changes) {
        return new BanIdReissueAll(store, newCrypto, newService, changes, CLOCK, meters);
    }

    private void insertPunishment(String id, String shortId) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb)", id, STAFF, STAFF, OWNER, shortId);
    }

    /** Punishments with a Ban ID under the old key; the plain old values by punishment. */
    private Map<String, String> givenPunishmentsUnderTheOldKey(int count) {
        Map<String, String> plain = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = String.format("p%03d", i);
            insertPunishment(id, String.format("%08X", i));
            assertTrue(oldService.issue(id));
            plain.put(id, oldCrypto.decrypt(store.encrypted(id).orElseThrow(), id));
        }
        return plain;
    }

    private String currentBanId(String id) {
        return newCrypto.decrypt(store.encrypted(id).orElseThrow(), id);
    }

    private int generation(String id) {
        return (int) db.count("SELECT ban_id_gen FROM punishments WHERE identifier = ?", id);
    }

    /** Fails the reissue of the given punishments, as a crash in the middle of a batch would. */
    private CaseChanges crashingOn(List<String> identifiers) {
        return (CaseChanges) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CaseChanges.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("reissueBanId") && identifiers.contains(args[0])) {
                throw new IllegalStateException("simulated crash");
            }
            try {
                return method.invoke(writer, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    void everyPunishmentGetsANewBanIdUnderTheNewKeyAndTheOldOnesNoLongerResolve() {
        Map<String, String> old = givenPunishmentsUnderTheOldKey(5);

        BanIdReissueAll.Result result = job(writer).run();

        assertEquals(5, result.reissued());
        assertEquals(0, result.failed());
        old.forEach((id, oldBanId) -> {
            String fresh = currentBanId(id);
            assertNotNull(fresh, "the new ciphertext is readable with the new key");
            assertFalse(fresh.equals(oldBanId), "a new value was drawn");
            assertEquals(2, generation(id), "the generation moved up once");
            assertEquals(id, newService.findIdentifier(fresh).orElseThrow(), "the new Ban ID logs in");
            assertTrue(newService.findIdentifier(oldBanId).isEmpty(), "the old Ban ID is dead");
            assertTrue(oldService.findIdentifier(oldBanId).isEmpty(), "and so is its old hash");
        });
    }

    @Test
    void aSecondRunChangesNothing() {
        givenPunishmentsUnderTheOldKey(4);
        job(writer).run();
        List<Integer> generations = new ArrayList<>();
        List<String> banIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            generations.add(generation(String.format("p%03d", i)));
            banIds.add(currentBanId(String.format("p%03d", i)));
        }
        long events = db.count("SELECT count(*) FROM case_event WHERE type = 'ban_id.reissued'");

        BanIdReissueAll.Result second = job(writer).run();

        assertEquals(0, second.reissued(), "nothing is reissued twice");
        assertEquals(4, second.scanned());
        for (int i = 0; i < 4; i++) {
            String id = String.format("p%03d", i);
            assertEquals(generations.get(i), generation(id));
            assertEquals(banIds.get(i), currentBanId(id));
        }
        assertEquals(events, db.count("SELECT count(*) FROM case_event WHERE type = 'ban_id.reissued'"), "no second event");
    }

    @Test
    void anInterruptedRunResumesAndReissuesOnlyWhatIsLeft() {
        givenPunishmentsUnderTheOldKey(6);
        BanIdReissueAll.Result interrupted = job(crashingOn(List.of("p002", "p004"))).run();
        assertEquals(4, interrupted.reissued());
        assertEquals(2, interrupted.failed());
        assertEquals(1, generation("p002"), "the crashed one is untouched");
        String done = currentBanId("p001");

        BanIdReissueAll.Result resumed = job(writer).run();

        assertEquals(2, resumed.reissued(), "only the two that were left");
        for (int i = 0; i < 6; i++) {
            assertEquals(2, generation(String.format("p%03d", i)), "reissued exactly once");
        }
        assertEquals(done, currentBanId("p001"), "the finished ones were not drawn again");
    }

    @Test
    void thePlayerSessionsOfEveryReissuedPunishmentAreRevoked() {
        givenPunishmentsUnderTheOldKey(3);
        for (String id : List.of("p000", "p001", "p002")) {
            db.execute("INSERT INTO player_session (token_hash, punishment_identifier, scope, csrf, created_at, last_seen_at, expires_at) "
                    + "VALUES (?, ?, 'FULL', 'csrf', 1, 1, 9999999999999)", "t-" + id, id);
        }

        job(writer).run();

        assertEquals(0, db.count("SELECT count(*) FROM player_session"));
    }

    @Test
    void aReissueEventNamingTheKeyRotationIsWrittenForEachPunishment() {
        givenPunishmentsUnderTheOldKey(3);

        job(writer).run();

        assertEquals(3, db.count("SELECT count(*) FROM case_event WHERE type = 'ban_id.reissued'"));
        assertEquals("KEY_ROTATION", db.text("SELECT payload ->> 'reason'" + EVENT_OF_P001));
        assertEquals("1", db.text("SELECT payload ->> 'fromGeneration'" + EVENT_OF_P001));
        assertEquals("2", db.text("SELECT payload ->> 'toGeneration'" + EVENT_OF_P001));
        assertEquals("SYSTEM", db.text("SELECT actor_trust" + EVENT_OF_P001));
        assertEquals(CLOCK.millis(), db.count("SELECT occurred_at" + EVENT_OF_P001));
    }

    @Test
    void aBanIdAlreadyUnderTheCurrentKeyIsLeftAlone() {
        givenPunishmentsUnderTheOldKey(2);
        insertPunishment("fresh", "FFFFFFF0");
        assertTrue(newService.issue("fresh"));
        String before = currentBanId("fresh");

        BanIdReissueAll.Result result = job(writer).run();

        assertEquals(2, result.reissued());
        assertEquals(1, generation("fresh"));
        assertEquals(before, currentBanId("fresh"));
    }

    @Test
    void progressIsCountedAndNoBanIdReachesLogsEventsOrMetrics() {
        Map<String, String> old = givenPunishmentsUnderTheOldKey(4);

        BanIdReissueAll.Result result = job(writer).run();

        assertEquals(4, result.reissued());
        assertEquals(4, meters.get("blackhole.banid.reissue.reissued").counter().count());
        List<String> secrets = new ArrayList<>(old.values());
        old.keySet().forEach(id -> secrets.add(currentBanId(id)));
        StringBuilder observed = new StringBuilder();
        appender.snapshot().forEach(e -> observed.append(e.getFormattedMessage()).append('\n'));
        for (Meter meter : meters.getMeters()) {
            observed.append(meter.getId()).append('\n');
        }
        db.column("SELECT payload::text FROM case_event").forEach(payload -> observed.append(payload).append('\n'));
        for (String secret : secrets) {
            assertFalse(observed.toString().contains(secret), "a Ban ID leaked into logs, metrics or events");
        }
    }
}
