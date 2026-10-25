package net.onelitefeather.blackhole.backend.kick;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.support.KickTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport.Published;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kick events go to every subscriber of the broker, so they carry no reason text, no staff name,
 * no IP address and no raw player UUID: only ids, the owner hash and the scope.
 */
class KickEventsNoLeakTest {

    private static final String REASON = "Spamming the lobby chat with advertisements";

    private final KickTestBed kicks = new KickTestBed();

    private Published onlyPublished() {
        assertEquals(1, kicks.transport.published().size(), "exactly one event published");
        return kicks.transport.published().getFirst();
    }

    /** The values of the payload as text, the way a subscriber would read them. */
    private static String text(Map<String, Object> payload) {
        return payload.toString();
    }

    @Test
    void aKickEventIsPublishedOnTheKickSubjectWithTheDocumentedFieldsOnly() {
        kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, KickScope.SERVER, "Lobby-1", REASON), "leak-1", ACTOR);

        Published published = onlyPublished();

        assertEquals("punishment.kick", published.eventType());
        assertEquals(Set.of("eventId", "kickId", "owner", "scope", "target", "occurredAt"), published.event().payload().keySet());
        assertEquals(published.event().eventId(), published.event().payload().get("eventId"), "the payload event id is the Nats-Msg-Id");
    }

    @Test
    void aNetworkKickEventHasNoTarget() {
        kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, REASON), "leak-2", ACTOR);

        assertFalse(onlyPublished().event().payload().containsKey("target"), "a network kick names no target");
    }

    @Test
    void aKickEventCarriesNoReasonNoStaffNameNoIpAndNoUuidBesideItsOwnIds() {
        kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, REASON), "leak-3", ACTOR);

        Map<String, Object> payload = onlyPublished().event().payload();
        String all = text(payload);

        assertFalse(all.contains("Spamming"), "no reason text: " + all);
        assertFalse(all.contains(ACTOR.displayName()), "no staff name: " + all);
        assertFalse(PiiGuard.containsIp(all), "no IP address: " + all);
        String stripped = all.replace((String) payload.get("eventId"), "").replace((String) payload.get("kickId"), "");
        assertFalse(PiiGuard.containsUuid(stripped),"no UUID but the event and kick ids: " + all);
        assertEquals(KickTestBed.OWNER, payload.get("owner"), "the owner is the hash");
    }

    @Test
    void aMassKickEventCarriesNoReasonText() {
        kicks.service.kickMass(new KickDTO.MassCreateRequest(KickScope.SERVER, "Lobby-1", REASON), "leak-4", ACTOR);

        Published published = onlyPublished();

        assertEquals("kick.mass", published.eventType());
        assertEquals(Set.of("eventId", "actionId", "scope", "target", "occurredAt"), published.event().payload().keySet());
        assertFalse(text(published.event().payload()).contains("Spamming"), "no reason text");
    }

    @Test
    void aFailedPublishDoesNotFailTheKickIsCountedAndLoggedWithoutTheReason() {
        SnapshotLogAppender appender = new SnapshotLogAppender();
        appender.start();
        List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(KickEventPublisher.class),
                (Logger) LoggerFactory.getLogger(net.onelitefeather.blackhole.backend.events.DomainEventPublisher.class));
        loggers.forEach(logger -> logger.addAppender(appender));
        try {
            kicks.transport.throwOnPublish(new IllegalStateException("broker down"));

            KickDTO result = kicks.service.kick(new KickDTO.CreateRequest(KickTestBed.OWNER, null, null, REASON), "leak-5", ACTOR);

            assertInstanceOf(KickDTO.Accepted.class, result, "a failed publish must not fail the request");
            assertEquals(1.0, kicks.meters.get(KickEventPublisher.FAILURE_METER).counter().count(), "the failure is counted");
            List<ILoggingEvent> lines = appender.snapshot();
            assertTrue(lines.stream().anyMatch(line -> line.getLevel() == Level.WARN), "the failure is logged as a warning: " + lines);
            lines.forEach(line -> assertFalse(line.getFormattedMessage().contains("Spamming"), "the log line leaks the reason: " + line));
        } finally {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
        }
    }
}
