package net.onelitefeather.blackhole.backend.publicapi.challenge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reserve with a fake {@code SET ... NX PX} that records its arguments and answers what the test asks. The
 * timeout test uses a one millisecond budget and a future that never completes; no test sleeps.
 */
class DragonflyChallengeReplayStoreTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    /** Records the last call and returns the reply the test set. */
    private static final class FakeSet implements DragonflyChallengeReplayStore.SetIfAbsent {
        String key;
        String value;
        Duration ttl;
        CompletionStage<String> reply = CompletableFuture.completedFuture("OK");

        @Override
        public CompletionStage<String> set(String key, String value, Duration ttl) {
            this.key = key;
            this.value = value;
            this.ttl = ttl;
            return this.reply;
        }
    }

    private static DragonflyChallengeReplayStore store(FakeSet fake, Duration timeout) {
        return new DragonflyChallengeReplayStore(fake, timeout);
    }

    @Test
    void anOkReplyReservesTheNonce() {
        FakeSet fake = new FakeSet();

        assertEquals(ChallengeReplayStore.Reservation.RESERVED, store(fake, TIMEOUT).reserve("nonce-1", Duration.ofSeconds(300)),
                "SET NX that stored the key answers OK, which reserves it");
    }

    @Test
    void aNullReplyIsAReplay() {
        FakeSet fake = new FakeSet();
        fake.reply = CompletableFuture.completedFuture(null);

        assertEquals(ChallengeReplayStore.Reservation.REPLAYED, store(fake, TIMEOUT).reserve("nonce-1", Duration.ofSeconds(300)),
                "SET NX that found the key answers null, which is a replay");
    }

    @Test
    void aReplyTooLateIsUnavailable() {
        FakeSet fake = new FakeSet();
        fake.reply = new CompletableFuture<>();

        assertEquals(ChallengeReplayStore.Reservation.UNAVAILABLE,
                store(fake, Duration.ofMillis(1)).reserve("nonce-1", Duration.ofSeconds(300)), "no answer inside the budget fails closed");
    }

    @Test
    void aFailedCallIsUnavailable() {
        FakeSet fake = new FakeSet();
        fake.reply = CompletableFuture.failedFuture(new IllegalStateException("connection refused"));

        assertEquals(ChallengeReplayStore.Reservation.UNAVAILABLE, store(fake, TIMEOUT).reserve("nonce-1", Duration.ofSeconds(300)),
                "an error from the server fails closed");
    }

    @Test
    void theKeyIsTheNonceUnderTheChallengePrefixWithValueOne() {
        FakeSet fake = new FakeSet();
        store(fake, TIMEOUT).reserve("nonce-1", Duration.ofSeconds(300));

        assertEquals("blackhole:challenge:used:nonce-1", fake.key, "the reserved key has the agreed name");
        assertEquals("1", fake.value, "the stored value is the constant 1");
    }

    @Test
    void theTtlIsTheRemainingLifetimePlusSixtySecondsOfMargin() {
        FakeSet fake = new FakeSet();
        store(fake, TIMEOUT).reserve("nonce-1", Duration.ofSeconds(300));

        assertEquals(Duration.ofSeconds(360), fake.ttl, "the key outlives the challenge by the clock-skew margin");
    }

    @Test
    void aLapsedChallengeStillGetsTheMarginAndNoNegativeTtl() {
        FakeSet fake = new FakeSet();
        store(fake, TIMEOUT).reserve("nonce-1", Duration.ofSeconds(-5));

        assertEquals(Duration.ofSeconds(60), fake.ttl, "a negative remaining lifetime counts as zero before the margin");
    }
}
