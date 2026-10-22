package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatBufferTest {

    private static final String ALICE = "a".repeat(128);
    private static final String BOB = "b".repeat(128);

    private final MutableClock clock = new MutableClock();

    private ChatBuffer buffer(int lines, String maxAge) {
        ChatCaptureConfig config = new ChatCaptureConfig();
        config.setEnabled(true);
        config.setLines(lines);
        config.setMaxAge(maxAge);
        return new ChatBuffer(clock, config);
    }

    private static List<String> texts(List<ChatBuffer.Line> lines) {
        return lines.stream().map(ChatBuffer.Line::text).toList();
    }

    @Test
    void aBufferWithTheDefaultConfigStoresNothing() {
        ChatBuffer buffer = new ChatBuffer(clock, new ChatCaptureConfig());

        buffer.record(ALICE, "lobby", "hello");

        assertFalse(buffer.enabled(), "capture must be off by default");
        assertTrue(buffer.linesOf(ALICE).isEmpty(), "a disabled buffer must not keep any chat");
        assertEquals(0, buffer.trackedPlayers(), "a disabled buffer must not even track the player");
    }

    @Test
    void onlyTheMostRecentLinesAreKept() {
        ChatBuffer buffer = buffer(3, "PT10M");

        for (int i = 1; i <= 5; i++) {
            buffer.record(ALICE, "lobby", "line " + i);
        }

        assertEquals(List.of("line 3", "line 4", "line 5"), texts(buffer.linesOf(ALICE)), "the oldest lines go first");
    }

    @Test
    void aLineOlderThanTheMaxAgeIsNoLongerReturned() {
        ChatBuffer buffer = buffer(10, "PT10M");
        buffer.record(ALICE, "lobby", "old");
        clock.advance(Duration.ofMinutes(6));
        buffer.record(ALICE, "lobby", "newer");

        clock.advance(Duration.ofMinutes(6));

        assertEquals(List.of("newer"), texts(buffer.linesOf(ALICE)), "only the line within 10 minutes may remain");
    }

    @Test
    void aLineExactlyAtTheMaxAgeIsStillReturned() {
        ChatBuffer buffer = buffer(10, "PT10M");
        buffer.record(ALICE, "lobby", "edge");

        clock.advance(Duration.ofMinutes(10));

        assertEquals(List.of("edge"), texts(buffer.linesOf(ALICE)), "the age limit is inclusive");
    }

    @Test
    void evictingExpiredLinesForgetsAPlayerWhoseLinesAllExpired() {
        ChatBuffer buffer = buffer(10, "PT10M");
        buffer.record(ALICE, "lobby", "gone soon");
        buffer.record(BOB, "lobby", "bob");
        clock.advance(Duration.ofMinutes(9));
        buffer.record(BOB, "lobby", "bob again");
        clock.advance(Duration.ofMinutes(2));

        buffer.evictExpired();

        assertEquals(1, buffer.trackedPlayers(), "alice has no line left and must not stay tracked");
        assertEquals(List.of("bob again"), texts(buffer.linesOf(BOB)), "bob keeps his line that is still young");
    }

    @Test
    void clearingAPlayerRemovesTheirLinesOnly() {
        ChatBuffer buffer = buffer(10, "PT10M");
        buffer.record(ALICE, "lobby", "alice");
        buffer.record(BOB, "lobby", "bob");

        buffer.clear(ALICE);

        assertTrue(buffer.linesOf(ALICE).isEmpty(), "the disconnected player's chat must be gone");
        assertEquals(List.of("bob"), texts(buffer.linesOf(BOB)), "other players are unaffected");
    }

    @Test
    void aLongMessageIsCutToTheTextLimit() {
        ChatBuffer buffer = buffer(10, "PT10M");

        buffer.record(ALICE, "lobby", "x".repeat(ChatBuffer.MAX_TEXT_LENGTH + 100));

        assertEquals(ChatBuffer.MAX_TEXT_LENGTH, buffer.linesOf(ALICE).getFirst().text().length(), "memory per line must be bounded");
    }

    @Test
    void linesOfOtherPlayersOnTheSameServerAreReturnedInSendingOrder() {
        ChatBuffer buffer = buffer(10, "PT10M");
        buffer.record(BOB, "lobby", "first");
        buffer.record(ALICE, "lobby", "mine");
        buffer.record(BOB, "bedwars", "elsewhere");
        buffer.record(BOB, "lobby", "second");

        List<ChatBuffer.Line> others = buffer.linesOnServerExcluding("lobby", ALICE);

        assertEquals(List.of("first", "second"), texts(others), "only bob's lines on that server, in the order they were sent");
    }

    @Test
    void linesOfOtherPlayersThatExpiredAreNotReturned() {
        ChatBuffer buffer = buffer(10, "PT10M");
        buffer.record(BOB, "lobby", "stale");
        clock.advance(Duration.ofMinutes(11));

        assertTrue(buffer.linesOnServerExcluding("lobby", ALICE).isEmpty(), "context must respect the max age as well");
    }
}
