package net.onelitefeather.blackhole.backend.evasion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.backend.evasion.dto.ClientFingerprintDTO;
import net.onelitefeather.blackhole.backend.evasion.service.ClientFingerprintService;
import net.onelitefeather.blackhole.backend.support.FakeClientFingerprintStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The raw brand, channels and mods are used once, to compute the hash, and then forgotten: they reach
 * no log line, event, stored row or returned value. The fingerprint itself stays out of events and
 * responses too.
 */
class ClientFingerprintNoLeakTest {

    private static final String BRAND = "zq-secret-brand";
    private static final String CHANNEL = "zq-secret-channel:main";
    private static final String MOD = "zq-secret-mod@1.2.3";
    private static final String A = "ab".repeat(64);
    private static final String B = "cd".repeat(64);

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        this.previousLevel = this.root.getLevel();
        this.root.setLevel(Level.ALL);
        this.appender.start();
        this.root.addAppender(this.appender);
    }

    @AfterEach
    void releaseLogs() {
        this.root.detachAppender(this.appender);
        this.root.setLevel(this.previousLevel);
    }

    @Test
    void submittingAndMatchingLeavesTheTraitsAndTheFingerprintOutOfLogsEventsRowsAndResponses() {
        FakeClientFingerprintStore store = new FakeClientFingerprintStore();
        RecordingEventPublisher events = new RecordingEventPublisher();
        ClientFingerprintService service = new ClientFingerprintService(
                store, new ClientFingerprintHasher(TestKeys.randomBytes()), events, new TestClock(), 14, 5);

        ClientFingerprintDTO.CreateRequest request = new ClientFingerprintDTO.CreateRequest(A, BRAND, List.of(CHANNEL), List.of(MOD));
        StringBuilder returned = new StringBuilder(String.valueOf(service.record(request))).append(request);
        returned.append(service.record(new ClientFingerprintDTO.CreateRequest(B, BRAND, List.of(CHANNEL), List.of(MOD))));
        returned.append(service.linksOf(A));
        returned.append(service.record(new ClientFingerprintDTO.CreateRequest(A, "x", List.of("a".repeat(129)), List.of(MOD + "\u0000"))));
        String fingerprint = store.all().getFirst().fingerprint();

        assertEquals(1, events.eventsOfType("evasion.client_match").size(), "precondition: the match was announced");
        for (ILoggingEvent line : this.appender.list) {
            assertClean(line.getFormattedMessage(), fingerprint);
            assertClean(String.valueOf(line.getThrowableProxy()), fingerprint);
        }
        events.events().forEach(event -> assertClean(event.type() + event.payload(), fingerprint));
        store.all().forEach(row -> assertClean(row.toString().replace(fingerprint, ""), fingerprint));
        assertClean(returned.toString(), fingerprint);
        assertFalse(returned.toString().contains(fingerprint), "no response carries the fingerprint");
    }

    private static void assertClean(String text, String fingerprint) {
        for (String secret : new String[]{BRAND, CHANNEL, MOD, "zq-secret"}) {
            assertFalse(text.contains(secret), "'" + secret + "' must not appear in: " + text);
        }
        assertFalse(text.contains(fingerprint), "the fingerprint must not appear in: " + text);
    }
}
