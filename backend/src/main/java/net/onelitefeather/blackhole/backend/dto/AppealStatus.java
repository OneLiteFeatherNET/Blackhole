package net.onelitefeather.blackhole.backend.dto;

/**
 * The lifecycle state of an appeal.
 */
public enum AppealStatus {

    SUBMITTED,
    ELIGIBLE_PENDING_REVIEW,
    IN_REVIEW,
    GRANTED_FULL_LIFT,
    GRANTED_DURATION_REDUCTION,
    DENIED,
    INELIGIBLE
}
