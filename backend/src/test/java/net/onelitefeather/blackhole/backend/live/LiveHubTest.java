package net.onelitefeather.blackhole.backend.live;

import net.onelitefeather.blackhole.backend.live.LiveTestDoubles.Collector;
import net.onelitefeather.blackhole.backend.live.LiveTestDoubles.FakeSessions;
import net.onelitefeather.blackhole.backend.live.LiveTestDoubles.FixedSequence;
import net.onelitefeather.blackhole.backend.live.LiveTestDoubles.ManualScheduler;
import net.onelitefeather.blackhole.backend.live.LiveTestDoubles.SharedTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live hub on virtual time: no sleep, no clock, no thread. Each test builds its own hub and doubles.
 */
class LiveHubTest {

    private static final String CASE = "PUNISHMENT-1";
    private static final String OTHER_CASE = "PUNISHMENT-2";
    private static final String SESSION = "session-hash-1";
    private static final String OTHER_SESSION = "session-hash-2";
    private static final LiveHub.Limits LIMITS = new LiveHub.Limits(3, Duration.ofSeconds(15), Duration.ofMinutes(30));
    private static final LiveHub.Subscription PLAYER = new LiveHub.Subscription(CASE, LiveAudience.PLAYER, SESSION);
    private static final LiveHub.Subscription STAFF = new LiveHub.Subscription(CASE, LiveAudience.STAFF, null);

    private ManualScheduler scheduler;
    private SharedTransport transport;
    private FixedSequence sequence;
    private FakeSessions sessions;
    private LiveHub hub;

    @BeforeEach
    void setUp() {
        this.scheduler = new ManualScheduler();
        this.transport = new SharedTransport();
        this.sequence = new FixedSequence();
        this.sessions = new FakeSessions();
        this.sessions.live(SESSION);
        this.sessions.live(OTHER_SESSION);
        this.hub = new LiveHub(this.transport, this.sequence, this.sessions, this.scheduler, LIMITS);
    }

    @Test
    void aNewStreamSendsTheRetryHintOnceThenReadyWithTheCurrentSeq() {
        this.sequence.set(CASE, LiveAudience.STAFF, 43);
        Collector staff = new Collector();

        this.hub.open(STAFF, null, staff, staff::end);

        assertEquals(List.of(new LiveFrame.Retry(Duration.ofSeconds(5)), new LiveFrame.Ready(43)), staff.frames);
    }

    @Test
    void aReconnectBelowTheCurrentSeqGetsAChangedRightAfterReady() {
        this.sequence.set(CASE, LiveAudience.PLAYER, 43);
        Collector player = new Collector();

        this.hub.open(PLAYER, "40", player, player::end);

        assertEquals(List.of(new LiveFrame.Retry(Duration.ofSeconds(5)), new LiveFrame.Ready(43), new LiveFrame.Changed(43)), player.frames);
    }

    @Test
    void aReconnectAtTheCurrentSeqGetsOnlyReady() {
        this.sequence.set(CASE, LiveAudience.PLAYER, 43);
        Collector player = new Collector();

        this.hub.open(PLAYER, "43", player, player::end);

        assertEquals(List.of(new LiveFrame.Retry(Duration.ofSeconds(5)), new LiveFrame.Ready(43)), player.frames);
    }

    @Test
    void aMalformedLastEventIdIsIgnoredAndNoChangedIsSent() {
        this.sequence.set(CASE, LiveAudience.PLAYER, 43);
        Collector player = new Collector();

        this.hub.open(PLAYER, "forty", player, player::end);

        assertEquals(List.of(new LiveFrame.Retry(Duration.ofSeconds(5)), new LiveFrame.Ready(43)), player.frames);
    }

    @Test
    void aPlayerSignalReachesThePlayerAndTheStaffStreamOfTheCase() {
        Collector player = new Collector();
        Collector staff = new Collector();
        this.hub.open(PLAYER, null, player, player::end);
        this.hub.open(STAFF, null, staff, staff::end);
        player.frames.clear();
        staff.frames.clear();

        this.transport.publish(ChangeSignal.changed(CASE, 44, LiveAudience.PLAYER));

        assertEquals(List.of(new LiveFrame.Changed(44)), player.frames);
        assertEquals(List.of(new LiveFrame.Changed(44)), staff.frames);
    }

    @Test
    void aStaffOnlySignalReachesOnlyTheStaffStream() {
        Collector player = new Collector();
        Collector staff = new Collector();
        this.hub.open(PLAYER, null, player, player::end);
        this.hub.open(STAFF, null, staff, staff::end);
        player.frames.clear();
        staff.frames.clear();

        this.transport.publish(ChangeSignal.changed(CASE, 45, LiveAudience.STAFF));

        assertEquals(List.of(), player.frames, "a staff note is never sent to the player's stream");
        assertEquals(List.of(new LiveFrame.Changed(45)), staff.frames);
    }

    @Test
    void aSignalForAnotherCaseReachesNobody() {
        Collector staff = new Collector();
        this.hub.open(STAFF, null, staff, staff::end);
        staff.frames.clear();

        this.transport.publish(ChangeSignal.changed(OTHER_CASE, 46, LiveAudience.PLAYER));

        assertEquals(List.of(), staff.frames);
    }

    @Test
    void aSessionsEndedSignalExpiresThePlayerStreamsOfTheCaseAndLeavesStaffOpen() {
        Collector player = new Collector();
        Collector staff = new Collector();
        this.hub.open(PLAYER, null, player, player::end);
        this.hub.open(STAFF, null, staff, staff::end);
        player.frames.clear();
        staff.frames.clear();

        this.transport.publish(ChangeSignal.sessionsEnded(CASE));

        assertEquals(List.of(new LiveFrame.Expired()), player.frames);
        assertTrue(player.ended, "the player's stream is closed after expired");
        assertFalse(staff.ended);
        assertEquals(List.of(), staff.frames);
    }

    @Test
    void aKeepAliveIsSentEveryFifteenSecondsWhileTheSessionLives() {
        Collector player = new Collector();
        this.hub.open(PLAYER, null, player, player::end);
        player.frames.clear();

        this.scheduler.advance(Duration.ofSeconds(14));
        assertEquals(List.of(), player.frames, "not before the keep-alive interval");

        this.scheduler.advance(Duration.ofSeconds(1));
        assertEquals(List.of(new LiveFrame.KeepAlive()), player.frames);

        this.scheduler.advance(Duration.ofSeconds(15));
        assertEquals(List.of(new LiveFrame.KeepAlive(), new LiveFrame.KeepAlive()), player.frames);
    }

    @Test
    void aKeepAliveOfAnEndedSessionSendsExpiredAndEnds() {
        Collector player = new Collector();
        this.hub.open(PLAYER, null, player, player::end);
        player.frames.clear();

        this.sessions.end(SESSION);
        this.scheduler.advance(Duration.ofSeconds(15));

        assertEquals(List.of(new LiveFrame.Expired()), player.frames);
        assertTrue(player.ended);
        assertEquals(0, this.scheduler.pending(), "no keep-alive is left behind for a closed stream");
    }

    @Test
    void aSecondStreamOfTheSameSessionReplacesTheFirst() {
        Collector first = new Collector();
        Collector second = new Collector();
        this.hub.open(PLAYER, null, first, first::end);

        this.hub.open(PLAYER, null, second, second::end);

        assertEquals(new LiveFrame.Replaced(), first.frames.getLast());
        assertTrue(first.ended);
        assertFalse(second.ended);
        assertEquals(1, this.hub.openStreams());
    }

    @Test
    void aStreamOfAnotherSessionDoesNotReplaceThisOne() {
        Collector first = new Collector();
        this.hub.open(PLAYER, null, first, first::end);

        this.hub.open(new LiveHub.Subscription(CASE, LiveAudience.PLAYER, OTHER_SESSION), null, new Collector(), () -> { });

        assertFalse(first.ended);
        assertEquals(2, this.hub.openStreams());
    }

    @Test
    void aStreamBeyondTheCapacityIsRefusedUntilAnotherCloses() {
        Collector a = new Collector();
        Collector b = new Collector();
        Collector c = new Collector();
        LiveHub.Stream held = this.hub.open(new LiveHub.Subscription(CASE, LiveAudience.STAFF, null), null, a, a::end);
        this.hub.open(new LiveHub.Subscription(OTHER_CASE, LiveAudience.STAFF, null), null, b, b::end);
        this.hub.open(new LiveHub.Subscription(CASE, LiveAudience.PLAYER, null), null, c, c::end);
        Collector refused = new Collector();

        assertThrows(LiveHub.CapacityExceeded.class, () -> this.hub.open(STAFF, null, refused, refused::end));
        assertEquals(0, refused.frames.size(), "a refused stream gets no frame");

        held.close();
        this.hub.open(STAFF, null, new Collector(), () -> { });
        assertEquals(3, this.hub.openStreams(), "a closed stream frees its slot");
    }

    @Test
    void aStreamEndsAtItsMaximumLifetimeWithoutAFrame() {
        Collector player = new Collector();
        this.hub.open(PLAYER, null, player, player::end);
        player.frames.clear();

        this.scheduler.advance(Duration.ofMinutes(30));

        assertTrue(player.ended);
        assertTrue(player.frames.stream().allMatch(frame -> frame instanceof LiveFrame.KeepAlive),
                "only keep-alives before the end, no expired or changed: the client simply reconnects " + player.frames);
    }

    @Test
    void closingAStreamStopsItsFramesAndItsTimers() {
        Collector player = new Collector();
        LiveHub.Stream stream = this.hub.open(PLAYER, null, player, player::end);
        player.frames.clear();

        stream.close();
        this.transport.publish(ChangeSignal.changed(CASE, 47, LiveAudience.PLAYER));
        this.scheduler.advance(Duration.ofMinutes(31));

        assertTrue(player.ended);
        assertEquals(List.of(), player.frames);
        assertEquals(0, this.scheduler.pending());
        assertEquals(0, this.hub.openStreams());
    }

    @Test
    void aSignalFromAnotherReplicaReachesAStreamOnThisOne() {
        Collector staffHere = new Collector();
        this.hub.open(STAFF, null, staffHere, staffHere::end);
        staffHere.frames.clear();
        LiveHub otherReplica = new LiveHub(this.transport, this.sequence, this.sessions, new ManualScheduler(), LIMITS);
        Collector staffThere = new Collector();
        otherReplica.open(STAFF, null, staffThere, staffThere::end);
        staffThere.frames.clear();

        this.transport.publish(ChangeSignal.changed(CASE, 48, LiveAudience.STAFF));

        assertEquals(List.of(new LiveFrame.Changed(48)), staffHere.frames);
        assertEquals(List.of(new LiveFrame.Changed(48)), staffThere.frames, "both replicas see the one signal");
    }

    @Test
    void aStreamWhoseSinkFailsIsReleased() {
        LiveHub.Stream stream = this.hub.open(STAFF, null, frame -> {
            throw new IllegalStateException("the client went away");
        }, () -> { });

        this.transport.publish(ChangeSignal.changed(CASE, 49, LiveAudience.STAFF));

        assertEquals(0, this.hub.openStreams(), "the stream is gone after its sink failed: " + stream);
    }
}
