package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/**
 * Generates and stores the protocol of an appeal. This is the small internal surface other parts of the
 * backend call: the appeal decision, the reconciler and, later, the route that closes a Discord ticket
 * (which calls it with {@code force} in the request that accepted the completeness proof, and answers 503
 * when the outcome is {@link Outcome#FAILED} or {@link Outcome#TOO_LARGE}).
 *
 * <p>It never throws: every outcome, including a storage failure, is a {@link Result}. It reads only the
 * stored case events and appeal rows.</p>
 */
public interface AppealProtocolGenerator {

    /** What a generation did. */
    enum Outcome {

        /** A new version was stored. */
        STORED,

        /** The source did not change; the existing active version is named. */
        UNCHANGED,

        /** The conversation changed less than the settle delay ago and the call was not forced; nothing was stored. */
        NOT_SETTLED,

        /** The appeal is not decided yet. */
        NOT_DECIDED,

        /** The appeal has a Discord ticket whose transcript was never verified complete; no protocol is made. */
        TICKET_INCOMPLETE,

        /** There is no such appeal. */
        NOT_FOUND,

        /** A file exceeds {@code blackhole.protocol.max-bytes}; nothing was stored. */
        TOO_LARGE,

        /** Storing failed; the reconciler tries again. */
        FAILED;

        /** Whether the appeal has a current protocol after this outcome. */
        public boolean hasProtocol() {
            return this == STORED || this == UNCHANGED;
        }
    }

    /**
     * @param version    the stored or existing version, for {@link Outcome#STORED} and {@link Outcome#UNCHANGED}
     * @param evidenceId the evidence item holding that version
     * @param entries    how many entries the protocol lists
     */
    record Result(Outcome outcome, @Nullable Integer version, @Nullable UUID evidenceId, int entries) {

        public static Result of(Outcome outcome) {
            return new Result(outcome, null, null, 0);
        }
    }

    /**
     * Generates the protocol of the appeal and stores it as a new version when the source events changed
     * since the active version (or there is none).
     *
     * @param force skip the settle delay ({@code blackhole.protocol.settle-delay}); a ticket that is not
     *              verified complete and an appeal that is not decided still make no protocol
     */
    Result generate(UUID appealId, boolean force);

    /**
     * Whether the appeal has a readable stored version whose source digest equals the digest of the events as they are
     * now. Stores nothing and never throws; {@code false} for an unknown or undecided appeal, a ticket that is not
     * verified complete and any failure. The Discord deletion gate asks this before a channel may go.
     */
    boolean isCurrent(UUID appealId);
}
