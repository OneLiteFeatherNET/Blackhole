package net.onelitefeather.blackhole.backend.live;

/**
 * A content-free hint that a case changed. It carries the case key (the punishment identifier), a seq, the audience and the kind,
 * and nothing else: no text, name, file name or identifier other than the seq, so it is safe to publish to every replica and to
 * log. A lost signal costs only latency, because the clients poll.
 *
 * @param caseKey  the punishment identifier
 * @param seq      the seq of the change, or 0 for {@link Kind#SESSIONS_ENDED}
 * @param audience who may hear about it
 * @param kind     what happened
 */
public record ChangeSignal(String caseKey, long seq, LiveAudience audience, Kind kind) {

    /** What the signal says. */
    public enum Kind {
        /** Something a reader may see changed up to {@code seq}. */
        CHANGED,
        /** The player sessions of the case ended (a leaked or reissued Ban ID): the player's streams close. */
        SESSIONS_ENDED
    }

    public static ChangeSignal changed(String caseKey, long seq, LiveAudience audience) {
        return new ChangeSignal(caseKey, seq, audience, Kind.CHANGED);
    }

    public static ChangeSignal sessionsEnded(String caseKey) {
        return new ChangeSignal(caseKey, 0L, LiveAudience.PLAYER, Kind.SESSIONS_ENDED);
    }
}
