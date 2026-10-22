package net.onelitefeather.blackhole.velocity.evidence;

import java.util.List;

/**
 * The chat lines that go into one piece of evidence, oldest first, already pseudonymised and
 * redacted: speakers are aliases that mean something in this snapshot only, never names, UUIDs or hashes.
 *
 * @param lines the lines
 */
public record ChatSnapshot(List<Entry> lines) {

    /**
     * One line of the snapshot.
     *
     * @param timestamp when the line was sent, epoch millis
     * @param speaker   the alias of the sender, for example {@code Spieler A}
     * @param text      the redacted text
     */
    public record Entry(long timestamp, String speaker, String text) {
    }

    public ChatSnapshot {
        lines = List.copyOf(lines);
    }

    /**
     * @return how many lines the snapshot has
     */
    public int size() {
        return this.lines.size();
    }
}
