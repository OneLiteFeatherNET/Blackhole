package net.onelitefeather.blackhole.backend.dto;

/**
 * Why an {@code EloEventEntity} happened. This is the audit trail that lets a reviewer (or a
 * future Phase 6 appeal) tell an algorithmic decision apart from a human one.
 */
public enum EloReasonCode {

    TOXICITY_FLAG,
    ANTICHEAT_FLAG,
    REPORT_ACTIONED,
    MANUAL_ADJUSTMENT,
    DECAY_RECOVERY,
    THRESHOLD_BAN
}
