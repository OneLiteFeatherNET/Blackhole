package net.onelitefeather.blackhole.backend.appeal;

/**
 * The outcome of {@code AppealDecisionService.reviewAppeal} - every rejection reason the review
 * endpoint can hit, plus the decided appeal on success, so {@code AppealController} only has to
 * map this 1:1 onto an {@code HttpResponse} rather than re-deriving why a review was rejected.
 *
 * @param kind    which outcome this is
 * @param message the human-readable rejection reason; {@code null} for {@link Kind#NOT_FOUND}
 *                and {@link Kind#DECIDED}
 * @param appeal  the updated, saved appeal; only present for {@link Kind#DECIDED}
 */
public record AppealReviewResult(Kind kind, String message, AppealEntity appeal) {

    public enum Kind {
        NOT_FOUND,
        NOT_AWAITING_REVIEW,
        INVALID_DECISION,
        SELF_REVIEW,
        SEVERE_FULL_LIFT_DISALLOWED,
        DURATION_REDUCTION_MISSING_EXPIRY,
        DURATION_REDUCTION_EXPIRY_NOT_FUTURE,
        PUNISHMENT_NOT_ACTIVE,
        DECIDED
    }

    public static AppealReviewResult notFound() {
        return new AppealReviewResult(Kind.NOT_FOUND, null, null);
    }

    public static AppealReviewResult notAwaitingReview() {
        return new AppealReviewResult(Kind.NOT_AWAITING_REVIEW, "Appeal is not awaiting review", null);
    }

    public static AppealReviewResult invalidDecision(String message) {
        return new AppealReviewResult(Kind.INVALID_DECISION, message, null);
    }

    public static AppealReviewResult selfReview() {
        return new AppealReviewResult(Kind.SELF_REVIEW, "Reviewer must not be the punishment's original source", null);
    }

    public static AppealReviewResult severeFullLiftDisallowed() {
        return new AppealReviewResult(
                Kind.SEVERE_FULL_LIFT_DISALLOWED, "SEVERE punishments can only receive a duration reduction, never a full lift", null
        );
    }

    public static AppealReviewResult durationReductionMissingExpiry() {
        return new AppealReviewResult(
                Kind.DURATION_REDUCTION_MISSING_EXPIRY, "newExpirationAt is required for GRANTED_DURATION_REDUCTION", null
        );
    }

    public static AppealReviewResult durationReductionExpiryNotFuture() {
        return new AppealReviewResult(Kind.DURATION_REDUCTION_EXPIRY_NOT_FUTURE, "newExpirationAt must be in the future", null);
    }

    public static AppealReviewResult punishmentNotActive() {
        return new AppealReviewResult(Kind.PUNISHMENT_NOT_ACTIVE, "Punishment is no longer active", null);
    }

    public static AppealReviewResult decided(AppealEntity appeal) {
        return new AppealReviewResult(Kind.DECIDED, null, appeal);
    }
}
