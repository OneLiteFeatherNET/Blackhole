package net.onelitefeather.blackhole.backend.elo;

/**
 * Why an {@code EloEventEntity} happened. This is the audit trail that lets a reviewer (or a
 * future Phase 6 appeal) tell an algorithmic decision apart from a human one.
 *
 * <p>Stored by ordinal: only ever append new codes at the end.</p>
 */
public enum EloReasonCode {

    TOXICITY_FLAG,
    ANTICHEAT_FLAG,
    REPORT_ACTIONED,
    MANUAL_ADJUSTMENT,
    DECAY_RECOVERY,
    THRESHOLD_BAN,
    PUNISHMENT_APPLIED,
    REPORT_REWARDED,

    /** A permanent punishment was lifted and the section score reset to the section's pardon score. */
    PARDON_RESET,

    /** A granted appeal gave back the ELO cost of a staff temporary punishment. */
    APPEAL_REFUND
}
