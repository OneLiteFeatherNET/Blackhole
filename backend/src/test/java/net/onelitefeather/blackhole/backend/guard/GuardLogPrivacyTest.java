package net.onelitefeather.blackhole.backend.guard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.backend.areaban.AreaBanCache;
import net.onelitefeather.blackhole.backend.areaban.AreaBanService;
import net.onelitefeather.blackhole.backend.areaban.InMemoryAreaBanStore;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every log line written while a burst ban, a refusal and a summary are processed leaves out the address and the owner. */
class GuardLogPrivacyTest {

    private static final String IP = "192.0.2.10";
    private static final String OWNER = "f".repeat(128);
    private static final String PLAYER_NAME = "Steve";
    private static final long START = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();

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
    void noLogLineWhileABurstBanARefusalAndASummaryAreProcessedCarriesTheAddressOrTheOwner() {
        RecordingEventTransport transport = new RecordingEventTransport();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(START), ZoneOffset.UTC);
        InMemoryAreaBanStore areas = new InMemoryAreaBanStore();
        AreaBanService bans = new AreaBanService(areas, new AreaBanCache(areas),
                new DomainEventPublisher(transport, clock), clock);
        InMemoryGuardRefusalStore refusals = new InMemoryGuardRefusalStore();
        GuardRefusalService service = new GuardRefusalService(refusals, bans,
                new BurstSettings(true, 2, Duration.ofMinutes(5), Duration.ofHours(2)));

        service.recordRefusal(64500L, "Example Hosting", "DE", START);
        service.recordRefusal(64500L, "Example Hosting", "DE", START + 1);
        new GuardSummaryService(refusals, new DomainEventPublisher(transport, clock), clock).publish();

        assertFalse(this.appender.list.isEmpty(), "the burst ban must have logged, so the check below is meaningful");
        for (ILoggingEvent event : this.appender.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains(IP), "no log line may carry an address: " + line);
            assertFalse(line.contains(OWNER), "no log line may carry an owner hash: " + line);
            assertFalse(line.contains(PLAYER_NAME), "no log line may carry a player name: " + line);
        }
        assertTrue(transport.published().size() >= 2, "the burst ban and the summary must both have published");
    }
}
