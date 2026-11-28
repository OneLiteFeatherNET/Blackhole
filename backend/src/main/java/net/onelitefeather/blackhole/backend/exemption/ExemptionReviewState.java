package net.onelitefeather.blackhole.backend.exemption;

/** Where an {@link ExemptionReviewEntity} stands. Only {@code OPEN} can be decided. */
public enum ExemptionReviewState {
    OPEN,
    APPLIED,
    DISMISSED
}
