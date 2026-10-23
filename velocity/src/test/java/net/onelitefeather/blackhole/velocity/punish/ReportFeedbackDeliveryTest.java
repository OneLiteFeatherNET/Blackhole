package net.onelitefeather.blackhole.velocity.punish;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportFeedbackResponse;
import net.onelitefeather.blackhole.client.model.ReportStatus;
import net.onelitefeather.blackhole.velocity.report.ReportFeedbackDelivery;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling reporters what became of their reports, with a stub backend and a recording player. */
class ReportFeedbackDeliveryTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String HASH = UUIDConverter.convertToSHA(PLAYER);
    private static final long DAY = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli();

    private final StubGateway backend = new StubGateway();
    private final List<Component> told = new ArrayList<>();
    private final Player player = (Player) Proxy.newProxyInstance(ReportFeedbackDeliveryTest.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER;
                case "sendMessage" -> {
                    if (args.length > 0 && args[0] instanceof Component component) {
                        told.add(component);
                    }
                    yield null;
                }
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private final List<Runnable> queued = new ArrayList<>();
    private final ReportFeedbackDelivery delivery = new ReportFeedbackDelivery(backend, () -> ZoneOffset.UTC, queued::add);

    private static ReportFeedbackResponse message(ReportStatus outcome) {
        return new ReportFeedbackResponse().identifier(UUID.randomUUID()).reportIdentifier(UUID.randomUUID())
                .category(ReportCategory.CHEATING).outcome(outcome).createdAt(DAY).expiresAt(DAY + 1000);
    }

    @Test
    void everyWaitingMessageIsShownAndThenAcknowledged() {
        ReportFeedbackResponse actioned = message(ReportStatus.ACTIONED);
        ReportFeedbackResponse dismissed = message(ReportStatus.DISMISSED);
        backend.feedback = List.of(actioned, dismissed);

        delivery.deliver(player);

        assertEquals(List.of(HASH), backend.feedbackOwners, "the backend is asked by owner hash, never by UUID");
        assertEquals(2, told.size());
        assertEquals(List.of(actioned.getIdentifier(), dismissed.getIdentifier()), backend.acknowledged);
    }

    @Test
    void nothingWaitingMeansNothingIsSaid() {
        delivery.deliver(player);

        assertTrue(told.isEmpty());
        assertTrue(backend.acknowledged.isEmpty());
    }

    @Test
    void aBackendFailureIsSwallowedSoTheLoginIsNotAffected() {
        backend.feedbackFailure = new ApiException(500, "boom", null, null);

        delivery.deliver(player);

        assertTrue(told.isEmpty());
    }

    @Test
    void aFailedAcknowledgementStillShowedTheMessage() {
        backend.feedback = List.of(message(ReportStatus.ACTIONED));
        backend.acknowledgeFailure = new ApiException(500, "boom", null, null);

        delivery.deliver(player);

        assertEquals(1, told.size(), "a failed acknowledgement may repeat a message later but never loses it");
    }

    @Test
    void aMessageIsNotShownTwiceWhenLoginAndPokeRace() {
        backend.feedback = List.of(message(ReportStatus.DISMISSED));

        delivery.deliver(player);
        delivery.deliver(player);

        assertEquals(1, told.size());
    }

    @Test
    void aPokeReachesOnlyTheReporterWhoIsOnThisProxy() {
        backend.feedback = List.of(message(ReportStatus.ACTIONED));

        delivery.poked("ff".repeat(64), List.of(player));
        assertTrue(queued.isEmpty(), "somebody else's poke must not fetch anything");

        delivery.poked(HASH, List.of(player));
        assertEquals(1, queued.size(), "the backend calls run off the event thread");
        queued.forEach(Runnable::run);
        assertEquals(1, told.size());
    }
}
