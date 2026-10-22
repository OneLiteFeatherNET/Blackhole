package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the buffer into the evidence of one punished player: all their buffered lines plus up to
 * {@code context-lines} lines of other players on the same server that preceded them, so the
 * conversation can be understood. Every speaker, the target included, becomes an alias
 * ({@code Spieler A}, {@code Spieler B}, ...) assigned in order of first appearance within this one
 * snapshot, so an alias cannot be correlated across evidence items. Texts are redacted and capped.
 */
public final class ChatSnapshotBuilder {

    private static final String ALIAS_PREFIX = "Spieler ";

    private final ChatBuffer buffer;
    private final int contextLines;

    public ChatSnapshotBuilder(@NotNull ChatBuffer buffer, @NotNull ChatCaptureConfig config) {
        this.buffer = buffer;
        this.contextLines = config.getContextLines();
    }

    /**
     * @param target the owner hash of the punished player
     * @return the snapshot, or empty if the player has no buffered chat
     */
    public @NotNull Optional<ChatSnapshot> build(@NotNull String target) {
        List<ChatBuffer.Line> own = this.buffer.linesOf(target);
        if (own.isEmpty()) {
            return Optional.empty();
        }
        ChatBuffer.Line newest = own.getLast();
        int room = Math.min(this.contextLines, ChatCaptureConfig.MAX_LINES - own.size());

        List<ChatBuffer.Line> picked = new ArrayList<>(own);
        if (room > 0) {
            List<ChatBuffer.Line> before = this.buffer.linesOnServerExcluding(newest.server(), target).stream()
                    .filter(line -> line.sequence() < newest.sequence())
                    .toList();
            picked.addAll(before.subList(Math.max(0, before.size() - room), before.size()));
        }
        picked.sort(Comparator.comparingLong(ChatBuffer.Line::sequence));

        Map<String, String> aliases = new HashMap<>();
        List<ChatSnapshot.Entry> entries = new ArrayList<>(picked.size());
        for (ChatBuffer.Line line : picked) {
            String alias = aliases.computeIfAbsent(line.owner(), owner -> alias(aliases.size()));
            entries.add(new ChatSnapshot.Entry(line.timestamp(), alias, cap(ChatRedactor.redact(line.text()))));
        }
        return Optional.of(new ChatSnapshot(entries));
    }

    private static String alias(int index) {
        return ALIAS_PREFIX + (index < 26 ? String.valueOf((char) ('A' + index)) : String.valueOf(index + 1));
    }

    private static String cap(String text) {
        return text.length() > ChatBuffer.MAX_TEXT_LENGTH ? text.substring(0, ChatBuffer.MAX_TEXT_LENGTH) : text;
    }
}
