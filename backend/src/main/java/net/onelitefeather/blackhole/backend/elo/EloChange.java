package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.Optional;

/**
 * Outcome of a score change, so callers see what happened instead of catching exceptions.
 */
public sealed interface EloChange {

    /**
     * The delta was applied.
     *
     * @param section     the section whose score changed
     * @param scoreBefore the score before the delta (after any pending decay)
     * @param scoreAfter  the score after the delta
     * @param automatic   the punishment the system applied because of this change, if any
     */
    record Applied(String section, int scoreBefore, int scoreAfter, Optional<AutomaticPunishment> automatic) implements EloChange {
    }

    /**
     * The delta was refused and nothing changed (unknown or disabled section).
     */
    record Rejected(ErrorKind kind, String message) implements EloChange {
    }

    /**
     * A punishment the ELO system applied on its own.
     *
     * @param tier                 which threshold triggered it
     * @param punishmentIdentifier the identifier of the created punishment
     */
    record AutomaticPunishment(Tier tier, String punishmentIdentifier) {
    }

    /** The threshold tier an automatic punishment belongs to. */
    enum Tier {
        SOFT,
        PERMANENT
    }
}
