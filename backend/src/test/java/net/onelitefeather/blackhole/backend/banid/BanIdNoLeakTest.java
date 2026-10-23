package net.onelitefeather.blackhole.backend.banid;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.banid.dto.BanIdDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Ban ID is a credential: it appears in the reveal response and nowhere else. */
class BanIdNoLeakTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
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
    void issueRevealLookupAndBackfillLeaveTheBanIdOutOfLogsEventsAndMetrics() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        PunishmentDTO.ApplyResponse applied = assertInstanceOf(PunishmentDTO.ApplyResponse.class,
                bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR));
        String identifier = applied.punishment().identifier();
        bed.banIds.store.givenPunishment(identifier);
        bed.banIds.store.givenPunishment("older-punishment");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new BanIdBackfill(bed.banIds.store, bed.banIds.service, meters).run();
        String banId = bed.banIds.crypto.decrypt(bed.banIds.store.encrypted().get(identifier), identifier);
        BanIdDTO revealed = bed.banIds.service.reveal(identifier);
        PunishmentDTO lookup = bed.punishmentService.findByBanId(new PunishmentDTO.LookupRequest("#" + banId));
        bed.banIds.service.containsBanId("a note with #" + banId);
        BanIdDTO notFound = bed.banIds.service.reveal("#" + banId);

        assertEquals(banId, assertInstanceOf(BanIdDTO.Response.class, revealed).banId(), "precondition: the reveal does hold it");
        assertInstanceOf(PunishmentDTO.LookupResponse.class, lookup);
        assertFalse(this.appender.list.isEmpty(), "precondition: the flow logs something");
        for (ILoggingEvent line : this.appender.list) {
            assertNoBanId(banId, line.getFormattedMessage());
            assertNoBanId(banId, String.valueOf(line.getThrowableProxy()));
        }
        assertTrue(bed.eloBed.events.events().size() > 0, "precondition: the punishment published events");
        bed.eloBed.events.events().forEach(event -> assertNoBanId(banId, event.type() + event.payload()));
        meters.getMeters().forEach(meter -> assertNoBanId(banId, meter.getId().toString()));
        assertNoBanId(banId, String.valueOf(revealed));
        assertNoBanId(banId, String.valueOf(notFound));
        assertNoBanId(banId, String.valueOf(lookup));
    }

    private static void assertNoBanId(String banId, String text) {
        assertFalse(text.contains(banId), "the Ban ID must not appear in: " + text.replace(banId, "<ban id>"));
    }
}
