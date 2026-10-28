package net.onelitefeather.blackhole.velocity.nats;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.nats.NatsSyncService.ReportCreated;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.active;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reactions to each event and the trust rule, driven by calling the listener: no NATS client, no server. */
class NatsSyncServiceTest {

    private final PunishmentMirror mirror = new PunishmentMirror();
    private final List<PunishmentSyncMessage> punishments = new ArrayList<>();
    private final List<String> evicted = new ArrayList<>();
    private final List<String> feedback = new ArrayList<>();
    private final List<ReportCreated> reports = new ArrayList<>();
    private int sectionChanges;

    private final NatsSyncService service = new NatsSyncService(mirror, new NatsSyncService.Reactions(
            punishments::add, evicted::add, () -> sectionChanges++, reports::add, feedback::add, kick -> { }, mass -> { },
            warning -> { }), Runnable::run);

    private void publish(String subject, String payload) {
        String eventType = subject.substring("blackhole.events.".length());
        String envelope = "{\"eventId\":\"e1\",\"eventType\":\"" + eventType + "\",\"occurredAt\":1,\"payloadVersion\":1,\"payload\":" + payload + "}";
        service.message(subject, envelope.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCreatedPunishmentBecomesAnActiveSyncMessage() {
        publish(Subjects.PUNISHMENT_CREATED, "{\"owner\":\"" + OWNER + "\",\"punishmentIdentifier\":\"p1\",\"section\":\"network\","
                + "\"enforcement\":\"BAN\",\"permanent\":false,\"reason\":\"cheating\",\"automatic\":true,"
                + "\"templateIdentifier\":\"t1\",\"expiresAt\":5000}");

        assertEquals(new PunishmentSyncMessage(OWNER, "network", "BAN", PunishmentSyncMessage.STATE_SET, "p1", "t1", "cheating", 5000L, true),
                punishments.getFirst());
    }

    @Test
    void aPermanentPunishmentHasNoExpiry() {
        publish(Subjects.PUNISHMENT_UPDATED, "{\"owner\":\"" + OWNER + "\",\"punishmentIdentifier\":\"p1\",\"section\":\"chat\","
                + "\"enforcement\":\"MUTE\",\"permanent\":true,\"reason\":\"spam\"}");

        PunishmentSyncMessage message = punishments.getFirst();
        assertNull(message.expiresAt());
        assertFalse(message.automatic());
        assertTrue(message.isSet());
    }

    @Test
    void expiredAndRevokedPunishmentsBecomeClearedMessages() {
        String payload = "{\"owner\":\"" + OWNER + "\",\"punishmentIdentifier\":\"p1\",\"section\":\"chat\",\"enforcement\":\"MUTE\"}";
        publish(Subjects.PUNISHMENT_EXPIRED, payload);
        publish(Subjects.PUNISHMENT_REVOKED, payload.replace("}", ",\"revokedBy\":\"mod\",\"verified\":false}"));

        assertEquals(2, punishments.size());
        assertTrue(punishments.stream().noneMatch(PunishmentSyncMessage::isSet), "both end the punishment");
        assertEquals("p1", punishments.getFirst().punishmentIdentifier());
    }

    @Test
    void aSetMessageUpdatesALoadedPlayersCacheWhenHandedToTheMirror() {
        mirror.load(OWNER, List.of());
        publish(Subjects.PUNISHMENT_CREATED, "{\"owner\":\"" + OWNER + "\",\"punishmentIdentifier\":\"p1\",\"section\":\"chat\",\"permanent\":true,\"reason\":\"x\"}");

        mirror.apply(punishments.getFirst());

        assertEquals(1, mirror.active(OWNER).size());
    }

    @Test
    void anAppealResolutionEvictsTheOwner() {
        publish(Subjects.APPEAL_RESOLVED, "{\"appealIdentifier\":\"a1\",\"owner\":\"" + OWNER + "\",\"punishmentIdentifier\":\"p1\","
                + "\"section\":\"network\",\"decision\":\"GRANTED_FULL_LIFT\",\"enforcement\":\"BAN\"}");

        assertEquals(List.of(OWNER), evicted);
    }

    @Test
    void aSectionChangeTriggersASectionRefresh() {
        publish(Subjects.SECTION_CHANGED, "{\"section\":\"chat\",\"change\":\"UPDATED\"}");

        assertEquals(1, sectionChanges);
    }

    @Test
    void aReportEventCarriesHashesOnlyToTheReportReaction() {
        publish(Subjects.REPORT_CREATED, "{\"reportIdentifier\":\"r1\",\"reporterHash\":\"aa\",\"reportedHash\":\"bb\",\"category\":\"CHEATING\"}");

        assertEquals(new ReportCreated("r1", "aa", "bb", "CHEATING"), reports.getFirst());
    }

    @Test
    void reportFeedbackPokesTheReporter() {
        publish(Subjects.REPORT_FEEDBACK, "{\"feedbackIdentifier\":\"f1\",\"reportIdentifier\":\"r1\",\"reporterHash\":\"aa\",\"outcome\":\"ACTION_TAKEN\"}");

        assertEquals(List.of("aa"), feedback);
    }

    @Test
    void garbageAndIncompleteEventsAreIgnoredWithoutThrowing() {
        service.message(Subjects.PUNISHMENT_CREATED, "not json".getBytes(StandardCharsets.UTF_8));
        publish(Subjects.PUNISHMENT_CREATED, "{\"section\":\"chat\"}");
        publish(Subjects.REPORT_CREATED, "{}");
        publish("blackhole.events.case.created", "{}");

        assertTrue(punishments.isEmpty() && reports.isEmpty() && evicted.isEmpty());
    }

    @Test
    void aFailingReactionNeitherThrowsNorStopsLaterEvents() {
        List<String> seen = new ArrayList<>();
        NatsSyncService failing = new NatsSyncService(mirror, new NatsSyncService.Reactions(
                m -> {
                    seen.add(m.punishmentIdentifier());
                    throw new IllegalStateException("boom");
                }, evicted::add, () -> { }, reports::add, feedback::add, kick -> { }, mass -> { }, warning -> { }), Runnable::run);
        String payload = "{\"owner\":\"" + OWNER + "\",\"section\":\"chat\",\"permanent\":true,\"reason\":\"x\",\"punishmentIdentifier\":\"p1\"}";
        String envelope = "{\"payload\":" + payload + "}";

        failing.message(Subjects.PUNISHMENT_CREATED, envelope.getBytes(StandardCharsets.UTF_8));
        failing.message(Subjects.PUNISHMENT_CREATED, envelope.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("p1", "p1"), seen);
    }

    @Test
    void theCacheIsNotTrustedBeforeTheBusIsConnected() {
        assertFalse(service.trusted());
    }

    @Test
    void connectingTrustsAnEmptyCache() {
        mirror.load(OWNER, List.of(active("chat", EnforcementKind.MUTE, "p1", null)));

        service.connected();

        assertTrue(service.trusted());
        assertFalse(mirror.isLoaded(OWNER), "what was cached over HTTP while events were missed is dropped");
    }

    @Test
    void aRepeatedConnectedReportDoesNotDiscardTheCacheAgain() {
        service.connected();
        mirror.load(OWNER, List.of(active("chat", EnforcementKind.MUTE, "p1", null)));

        service.connected();

        assertTrue(mirror.isLoaded(OWNER), "the second report changes nothing while events keep the cache current");
    }

    @Test
    void aDisconnectDiscardsTheCacheAndStopsTrusting() {
        service.connected();
        mirror.load(OWNER, List.of(active("chat", EnforcementKind.MUTE, "p1", null)));

        service.disconnected();

        assertFalse(service.trusted(), "every check must go to the backend now");
        assertFalse(mirror.isLoaded(OWNER));
    }

    @Test
    void aReconnectDiscardsTheCacheOnceMore() {
        service.connected();
        service.disconnected();
        mirror.load(OWNER, List.of(active("chat", EnforcementKind.MUTE, "p1", null)));

        service.connected();

        assertTrue(service.trusted());
        assertFalse(mirror.isLoaded(OWNER), "state read over HTTP during the outage is refilled, not carried over");
    }
}
