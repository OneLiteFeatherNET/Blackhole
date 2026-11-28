package net.onelitefeather.blackhole.backend.warning;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport.Published;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warned event goes to every subscriber of the broker, so it carries no reason text, no staff name, no IP
 * address and no raw player UUID: only the event id, the warning's Ban ID, the owner hash and the time.
 */
class WarningEventsNoLeakTest {

    /** The template's reason is stored on the warning; it must never reach the event. */
    private static final String REASON = "reason of lobby-spam";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    /** Applies the warning template and returns the warning's identifier. */
    private String applyWarning() {
        bed.givenTemplate("lobby-spam", "network", "lobby-spam", new TemplateStep(StepKind.WARN, null, 0));
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "lobby-spam", null, null), ACTOR);
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the warning must be applied").punishment().identifier();
    }

    private Published onlyPublished() {
        assertEquals(1, bed.warningTransport.published().size(), "exactly one event published");
        return bed.warningTransport.published().getFirst();
    }

    @Test
    void aWarnedEventIsPublishedOnTheWarnedSubjectWithTheDocumentedFieldsOnly() {
        String warningId = applyWarning();

        Published published = onlyPublished();

        assertEquals("punishment.warned", published.eventType());
        assertEquals(Set.of("eventId", "warningId", "owner", "occurredAt"), published.event().payload().keySet());
        assertEquals(published.event().eventId(), published.event().payload().get("eventId"), "the payload event id is the Nats-Msg-Id");
        assertEquals(warningId, published.event().payload().get("warningId"), "the warning's Ban ID identifies it");
        assertEquals(OWNER, published.event().payload().get("owner"), "the owner is the hash");
    }

    @Test
    void aWarnedEventCarriesNoReasonNoStaffNameNoIpAndNoUuidBesideItsOwnIds() {
        applyWarning();

        Map<String, Object> payload = onlyPublished().event().payload();
        String all = payload.toString();

        assertFalse(all.contains("reason of"), "no reason text: " + all);
        assertFalse(all.contains(ACTOR.displayName()), "no staff name: " + all);
        assertFalse(PiiGuard.containsIp(all), "no IP address: " + all);
        String stripped = all.replace((String) payload.get("eventId"), "").replace((String) payload.get("warningId"), "");
        assertFalse(PiiGuard.containsUuid(stripped), "no UUID but the event id: " + all);
    }

    @Test
    void aFailedPublishDoesNotFailTheWarningIsCountedAndLoggedWithoutTheReason() {
        SnapshotLogAppender appender = new SnapshotLogAppender();
        appender.start();
        List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(WarningEventPublisher.class),
                (Logger) LoggerFactory.getLogger(DomainEventPublisher.class));
        loggers.forEach(logger -> logger.addAppender(appender));
        try {
            bed.warningTransport.throwOnPublish(new IllegalStateException("broker down"));

            String warningId = applyWarning();

            assertFalse(warningId.isBlank(), "a failed publish must not fail the warning");
            assertEquals(1.0, bed.warningMeters.get(WarningEventPublisher.FAILURE_METER).counter().count(), "the failure is counted");
            List<ILoggingEvent> lines = appender.snapshot();
            assertTrue(lines.stream().anyMatch(line -> line.getLevel() == Level.WARN), "the failure is logged as a warning: " + lines);
            lines.forEach(line -> assertFalse(line.getFormattedMessage().contains("reason of"), "the log line leaks the reason: " + line));
        } finally {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
        }
    }
}
