package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.kick.KickStore.Delivery;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The status of a kick is derived from the deadline, the clock and the stored acknowledgements; nothing waits. */
class KickStatusTest {

    private final TestClock clock = new TestClock();
    private final KickDeadline deadline = new KickDeadline(clock, Duration.ofSeconds(5));
    private final long deadlineAt = deadline.deadlineAt();

    private KickStatus player(Delivery... deliveries) {
        return KickStatus.derive(KickKind.PLAYER, deadlineAt, clock.millis(), KickDeliveries.of(List.of(deliveries)));
    }

    private KickStatus mass(Delivery... deliveries) {
        return KickStatus.derive(KickKind.MASS, deadlineAt, clock.millis(), KickDeliveries.of(List.of(deliveries)));
    }

    private static Delivery ack(String proxy, boolean found, boolean moved) {
        return new Delivery(proxy, found, moved, 1L);
    }

    @Test
    void theDeadlineIsTheTimeoutAfterNow() {
        assertEquals(clock.millis() + 5_000, deadlineAt, "now plus the configured ack timeout");
    }

    @Test
    void aKickWithoutAcknowledgementIsPendingBeforeTheDeadline() {
        clock.advance(Duration.ofMillis(4_999));

        assertEquals(KickStatus.PENDING, player(), "one millisecond before the deadline");
    }

    @Test
    void aKickWithoutPositiveAcknowledgementIsNotOnlineAtTheDeadline() {
        clock.advance(Duration.ofSeconds(5));

        assertEquals(KickStatus.NOT_ONLINE, player(), "exactly at the deadline");
    }

    @Test
    void aKickWithoutPositiveAcknowledgementIsNotOnlineAfterTheDeadline() {
        clock.advance(Duration.ofSeconds(60));

        assertEquals(KickStatus.NOT_ONLINE, player(ack("proxy-1", false, false)), "an answer that did not find the player is not positive");
    }

    @Test
    void aNegativeAcknowledgementBeforeTheDeadlineStaysPending() {
        assertEquals(KickStatus.PENDING, player(ack("proxy-1", false, false)), "another proxy may still find the player");
    }

    @Test
    void aMovedPlayerIsDeliveredEvenBeforeTheDeadline() {
        assertEquals(KickStatus.DELIVERED, player(ack("proxy-1", true, true)), "positive ack before the deadline");
    }

    @Test
    void aPlayerFoundButNotMovedIsNotOnTarget() {
        assertEquals(KickStatus.NOT_ON_TARGET, player(ack("proxy-1", true, false)), "found, nothing moved");
    }

    @Test
    void aPlayerFoundButNotMovedStaysNotOnTargetAfterTheDeadline() {
        clock.advance(Duration.ofSeconds(60));

        assertEquals(KickStatus.NOT_ON_TARGET, player(ack("proxy-1", true, false)), "a proxy did find the player, so never NOT_ONLINE");
    }

    @Test
    void aMoveByAnyProxyWinsOverAnotherProxyThatOnlyFoundThePlayer() {
        assertEquals(KickStatus.DELIVERED, player(ack("proxy-1", true, false), ack("proxy-2", true, true)), "delivered wins");
    }

    @Test
    void aMassKickIsDeliveredWithTheCountOfProxiesThatMovedPlayers() {
        KickDeliveries deliveries = KickDeliveries.of(List.of(ack("proxy-1", true, true), ack("proxy-2", true, true), ack("proxy-3", false, false)));

        assertEquals(KickStatus.DELIVERED, KickStatus.derive(KickKind.MASS, deadlineAt, clock.millis(), deliveries));
        assertEquals(2, deliveries.deliveredCount(), "proxies that reported a move");
    }

    @Test
    void aMassKickIsPendingBeforeTheDeadlineWithoutAcknowledgement() {
        assertEquals(KickStatus.PENDING, mass(), "nobody answered yet");
    }

    @Test
    void aMassKickNobodyAnsweredHasNoProxyAnswerAfterTheDeadline() {
        clock.advance(Duration.ofSeconds(5));

        assertEquals(KickStatus.NO_PROXY_ANSWER, mass(), "no proxy answered by the deadline");
    }

    @Test
    void aMassKickAnsweredWithoutAMoveIsDeliveredAfterTheDeadline() {
        clock.advance(Duration.ofSeconds(5));

        assertEquals(KickStatus.DELIVERED, mass(ack("proxy-1", false, false)), "a proxy answered, nobody was on the target");
    }

    @Test
    void aSingleKickIsNeverNoProxyAnswer() {
        clock.advance(Duration.ofSeconds(60));

        assertEquals(KickStatus.NOT_ONLINE, player(), "NO_PROXY_ANSWER is for mass kicks only");
    }
}
