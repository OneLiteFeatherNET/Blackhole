package net.onelitefeather.blackhole.velocity.intel;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.model.SessionOutcome;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static net.onelitefeather.blackhole.velocity.intel.SessionPlayer.ALICE_ID;
import static net.onelitefeather.blackhole.velocity.intel.SessionPlayer.ALICE_NAME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session reports with the backend stubbed: the executor runs each report at once, nothing touches the network. */
class SessionReporterTest {

    private static final Executor RUN_AT_ONCE = Runnable::run;
    private static final Executor QUEUE_FULL = task -> {
        throw new RejectedExecutionException("queue full");
    };

    private final AtomicLong sequence = new AtomicLong();
    private final RecordingSessionBackend backend = new RecordingSessionBackend();

    private SessionReporter reporter(Executor executor) {
        return new SessionReporter(backend, executor, () -> new UUID(0, sequence.incrementAndGet()));
    }

    @Test
    void anAllowedLoginSendsOneStartWithTheAllowedOutcome() {
        reporter(RUN_AT_ONCE).reportStart(SessionPlayer.alice(), SessionOutcome.ALLOWED);

        assertEquals(1, backend.starts().size(), "one start report per login");
        assertEquals(SessionOutcome.ALLOWED, backend.starts().getFirst().getOutcome());
    }

    @Test
    void aDeniedLoginSendsOneStartWithTheDeniedOutcome() {
        reporter(RUN_AT_ONCE).reportStart(SessionPlayer.alice(), SessionOutcome.DENIED);

        assertEquals(1, backend.starts().size(), "one start report per login");
        assertEquals(SessionOutcome.DENIED, backend.starts().getFirst().getOutcome());
    }

    @Test
    void theStartCarriesTheRawUuidTheNameAndTheHashedOwner() {
        reporter(RUN_AT_ONCE).reportStart(SessionPlayer.alice(), SessionOutcome.ALLOWED);

        var start = backend.starts().getFirst();
        assertEquals(ALICE_ID.toString(), start.getPlayerUuid(), "staff see who the session belongs to");
        assertEquals(ALICE_NAME, start.getPlayerName());
        assertEquals(UUIDConverter.convertToSHA(ALICE_ID), start.getOwner(), "the owner hash every proxy sends");
        assertEquals("play.example.net", start.getVirtualHost(), "the host typed, without the port");
    }

    @Test
    void aBackendThatIsUnavailableDoesNotThrowToTheCaller() {
        SessionReporter reporter = new SessionReporter(RecordingSessionBackend.unavailable(), RUN_AT_ONCE,
                () -> new UUID(0, sequence.incrementAndGet()));

        assertDoesNotThrow(() -> reporter.reportStart(SessionPlayer.alice(), SessionOutcome.ALLOWED),
                "a backend outage must never reach the login");
    }

    @Test
    void aFullQueueDropsTheStartWithoutThrowingToTheCaller() {
        SessionReporter reporter = reporter(QUEUE_FULL);

        assertDoesNotThrow(() -> reporter.reportStart(SessionPlayer.alice(), SessionOutcome.ALLOWED),
                "a full queue drops the report and returns at once");
        assertTrue(backend.starts().isEmpty(), "the dropped report is not sent later");
    }

    @Test
    void aBrandReportIsSentAsAnUpdateOfTheSessionOfTheConnection() {
        SessionReporter reporter = reporter(RUN_AT_ONCE);
        Player alice = SessionPlayer.alice();
        reporter.reportStart(alice, SessionOutcome.ALLOWED);

        reporter.reportBrand(alice, "vanilla");

        assertEquals(1, backend.updates().size(), "one brand update");
        var update = backend.updates().getFirst();
        assertEquals(backend.starts().getFirst().getSessionId(), update.sessionId(), "the session of this connection");
        assertEquals("vanilla", update.request().getClientBrand());
        assertFalse(update.request().getEnded(), "a brand does not end the session");
    }

    @Test
    void theBrandOfASecondConnectionOfTheSamePlayerGoesToItsOwnSession() {
        SessionReporter reporter = reporter(RUN_AT_ONCE);
        Player first = SessionPlayer.of(ALICE_ID, ALICE_NAME);
        Player second = SessionPlayer.of(ALICE_ID, ALICE_NAME);
        reporter.reportStart(first, SessionOutcome.ALLOWED);
        reporter.reportStart(second, SessionOutcome.ALLOWED);

        reporter.reportBrand(second, "fabric");

        assertEquals(backend.starts().get(1).getSessionId(), backend.updates().getFirst().sessionId(),
                "keyed by the connection, not by the player's UUID");
    }

    @Test
    void aDisconnectSendsTheEndOnce() {
        SessionReporter reporter = reporter(RUN_AT_ONCE);
        Player alice = SessionPlayer.alice();
        reporter.reportStart(alice, SessionOutcome.ALLOWED);

        reporter.reportEnd(alice);
        reporter.reportEnd(alice);

        assertEquals(1, backend.updates().size(), "the end is sent once");
        assertTrue(backend.updates().getFirst().request().getEnded(), "the update marks the session as ended");
    }

    @Test
    void aBrandAfterTheDisconnectIsNotSent() {
        SessionReporter reporter = reporter(RUN_AT_ONCE);
        Player alice = SessionPlayer.alice();
        reporter.reportStart(alice, SessionOutcome.ALLOWED);
        reporter.reportEnd(alice);

        reporter.reportBrand(alice, "vanilla");

        assertEquals(1, backend.updates().size(), "only the end, no brand after it");
    }

    @Test
    void aDeniedLoginHasNoSessionToEnd() {
        SessionReporter reporter = reporter(RUN_AT_ONCE);
        Player alice = SessionPlayer.alice();
        reporter.reportStart(alice, SessionOutcome.DENIED);

        reporter.reportEnd(alice);

        assertTrue(backend.updates().isEmpty(), "a denied connection never joins the game, so it never disconnects as a session");
    }
}
