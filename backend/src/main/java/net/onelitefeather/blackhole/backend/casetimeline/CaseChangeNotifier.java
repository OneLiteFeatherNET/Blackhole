package net.onelitefeather.blackhole.backend.casetimeline;

/**
 * Tells the live streams that a case changed. Every writer of case events calls it <b>after</b> its statement succeeded, so a
 * stream that is told about a change can read it. An implementation must never fail the write: the write is the truth, the signal is
 * a hint, and a lost hint costs only latency because the clients poll ({@code .claude/rules/events.md}).
 *
 * <p>{@code CaseChangeSurfaceTest} fails when a class writes {@code case_event} rows without holding this port.</p>
 */
public interface CaseChangeNotifier {

    /** Notifies nobody: for a writer built without live streams (a test of the write alone). */
    CaseChangeNotifier NONE = new CaseChangeNotifier() {
        @Override
        public void appended(String punishmentIdentifier, long seq, String type) {
        }

        @Override
        public void sessionsEnded(String punishmentIdentifier) {
        }

        @Override
        public void seenMoved(String punishmentIdentifier, long seq) {
        }
    };

    /** An event of the case was appended. */
    void appended(String punishmentIdentifier, long seq, String type);

    /** The player sessions of the case ended, because its Ban ID was marked leaked or reissued. */
    void sessionsEnded(String punishmentIdentifier);

    /** The player's conversation read moved the seen marker to {@code seq}; staff show "Seen" again. */
    void seenMoved(String punishmentIdentifier, long seq);

    /** The same for an appended event as returned by a {@link CaseChanges} statement. */
    default void appended(String punishmentIdentifier, CaseChanges.Appended event) {
        appended(punishmentIdentifier, event.seq(), event.type());
    }
}
