package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param decision       the reviewer's decision - only {@code GRANTED_FULL_LIFT},
 *                        {@code GRANTED_DURATION_REDUCTION}, or {@code DENIED} are valid here
 * @param decisionNote   an optional note explaining the decision
 * @param reviewerId     the staff/system identity making this decision; rejected if it matches
 *                        the punishment's own {@code source} (self-review exclusion)
 * @param newExpirationAt required when {@code decision} is {@code GRANTED_DURATION_REDUCTION} -
 *                        the new, sooner expiration timestamp (epoch millis)
 */
@Serdeable
@ReflectiveAccess
public record AppealReviewDTO(
        @NonNull AppealStatus decision,
        @Nullable @Size(max = 2000) String decisionNote,
        @NonNull UUID reviewerId,
        @Nullable Long newExpirationAt
) {
}
