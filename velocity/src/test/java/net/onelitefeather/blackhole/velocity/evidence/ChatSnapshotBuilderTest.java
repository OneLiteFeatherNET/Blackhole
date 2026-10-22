package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSnapshotBuilderTest {

    private static final String TARGET = "a".repeat(128);
    private static final String BOB = "b".repeat(128);
    private static final String CARL = "c".repeat(128);

    private final MutableClock clock = new MutableClock();
    private final ChatCaptureConfig config = new ChatCaptureConfig();
    private ChatBuffer buffer;

    private ChatSnapshotBuilder builder(int contextLines) {
        config.setEnabled(true);
        config.setContextLines(contextLines);
        buffer = new ChatBuffer(clock, config);
        return new ChatSnapshotBuilder(buffer, config);
    }

    private static List<String> speakers(ChatSnapshot snapshot) {
        return snapshot.lines().stream().map(ChatSnapshot.Entry::speaker).toList();
    }

    private static List<String> texts(ChatSnapshot snapshot) {
        return snapshot.lines().stream().map(ChatSnapshot.Entry::text).toList();
    }

    @Test
    void aPlayerWithoutBufferedChatHasNoSnapshot() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "lobby", "someone else talking");

        assertTrue(builder.build(TARGET).isEmpty(), "without the target's own lines there is nothing to attach");
    }

    @Test
    void theTargetsLinesAreReturnedInSendingOrder() {
        ChatSnapshotBuilder builder = builder(0);
        buffer.record(TARGET, "lobby", "one");
        buffer.record(TARGET, "lobby", "two");

        assertEquals(List.of("one", "two"), texts(builder.build(TARGET).orElseThrow()));
    }

    @Test
    void contextLinesOfOthersThatPrecededTheTargetAreInterleavedInOrder() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "lobby", "bob 1");
        buffer.record(TARGET, "lobby", "target 1");
        buffer.record(BOB, "lobby", "bob 2");
        buffer.record(TARGET, "lobby", "target 2");

        assertEquals(List.of("bob 1", "target 1", "bob 2", "target 2"), texts(builder.build(TARGET).orElseThrow()));
    }

    @Test
    void linesOfOthersAfterTheTargetsLastLineAreNotIncluded() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(TARGET, "lobby", "target");
        buffer.record(BOB, "lobby", "reply after the fact");

        assertEquals(List.of("target"), texts(builder.build(TARGET).orElseThrow()), "context is what preceded, not what followed");
    }

    @Test
    void onlyTheLatestContextLinesUpToTheLimitAreIncluded() {
        ChatSnapshotBuilder builder = builder(2);
        buffer.record(BOB, "lobby", "bob 1");
        buffer.record(BOB, "lobby", "bob 2");
        buffer.record(BOB, "lobby", "bob 3");
        buffer.record(TARGET, "lobby", "target");

        assertEquals(List.of("bob 2", "bob 3", "target"), texts(builder.build(TARGET).orElseThrow()));
    }

    @Test
    void contextLinesFromOtherServersAreNotIncluded() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "bedwars", "somewhere else");
        buffer.record(TARGET, "lobby", "target");

        assertEquals(List.of("target"), texts(builder.build(TARGET).orElseThrow()));
    }

    @Test
    void aContextLimitOfZeroIncludesNoOtherPlayer() {
        ChatSnapshotBuilder builder = builder(0);
        buffer.record(BOB, "lobby", "bob");
        buffer.record(TARGET, "lobby", "target");

        assertEquals(List.of("target"), texts(builder.build(TARGET).orElseThrow()), "context-lines 0 is the off switch for third-party chat");
    }

    @Test
    void theSnapshotNeverExceedsWhatTheBackendAccepts() {
        config.setLines(50);
        ChatSnapshotBuilder builder = builder(50);
        for (int i = 0; i < 50; i++) {
            buffer.record(BOB, "lobby", "bob " + i);
        }
        for (int i = 0; i < 40; i++) {
            buffer.record(TARGET, "lobby", "target " + i);
        }

        ChatSnapshot snapshot = builder.build(TARGET).orElseThrow();

        assertEquals(50, snapshot.size(), "50 is the backend's chat line limit");
        assertEquals(40, snapshot.lines().stream().filter(entry -> entry.text().startsWith("target")).count(),
                "the target's own lines win over context");
    }

    @Test
    void speakersAreAliasedInOrderOfFirstAppearanceAndTheTargetIsAliasedToo() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "lobby", "bob");
        buffer.record(TARGET, "lobby", "target 1");
        buffer.record(CARL, "lobby", "carl");
        buffer.record(BOB, "lobby", "bob again");
        buffer.record(TARGET, "lobby", "target 2");

        assertEquals(
                List.of("Spieler A", "Spieler B", "Spieler C", "Spieler A", "Spieler B"),
                speakers(builder.build(TARGET).orElseThrow()),
                "an alias must stay the same for one speaker within the snapshot");
    }

    @Test
    void aSpeakerGetsADifferentAliasInAnotherSnapshot() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "lobby", "bob");
        buffer.record(TARGET, "lobby", "target 1");
        String bobsAliasInTheFirst = speakers(builder.build(TARGET).orElseThrow()).getFirst();
        buffer.clear(BOB);
        buffer.record(TARGET, "lobby", "target 2");
        buffer.record(BOB, "lobby", "bob");
        buffer.record(TARGET, "lobby", "target 3");

        List<String> second = speakers(builder.build(TARGET).orElseThrow());

        assertEquals("Spieler A", bobsAliasInTheFirst);
        assertNotEquals(bobsAliasInTheFirst, second.get(second.size() - 2),
                "aliases come from the order in this snapshot, so they cannot be matched across evidence items");
    }

    @Test
    void speakersAreAliasesAndNeverOwnerHashes() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "lobby", "hi");
        buffer.record(TARGET, "lobby", "hello");

        ChatSnapshot snapshot = builder.build(TARGET).orElseThrow();

        for (ChatSnapshot.Entry entry : snapshot.lines()) {
            assertTrue(entry.speaker().startsWith("Spieler "), "only aliases may appear as speaker, got " + entry.speaker());
            assertFalse(entry.speaker().contains(TARGET) || entry.speaker().contains(BOB), "owner hashes must not leave the proxy");
        }
    }

    @Test
    void urlsAndIpAddressesAreRedactedInTheSnapshot() {
        ChatSnapshotBuilder builder = builder(5);
        buffer.record(BOB, "lobby", "go to https://evil.example/x");
        buffer.record(TARGET, "lobby", "my ip 203.0.113.5");

        assertEquals(List.of("go to [url]", "my ip [ip]"), texts(builder.build(TARGET).orElseThrow()));
    }
}
