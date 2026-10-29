package net.onelitefeather.blackhole.backend.appeal.dto;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
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
 * @param newExpirationAt required when {@code decision} is {@code GRANTED_DURATION_REDUCTION} -
 *                        the new, sooner expiration timestamp (epoch millis)
 * @param playerMessage  an optional message to the player, 1 to 2000 characters without a UUID, an IP address or a Ban ID; it is
 *                        recorded on the case as a staff message the player can read, while {@code decisionNote} stays internal
 * @param onBehalfOf     the staff member's oid, sent by the Discord bot only (the decision is theirs); see {@code StaffDelegation}
 */
@Serdeable
@ReflectiveAccess
public record AppealReviewDTO(
        @NonNull AppealStatus decision,
        @Nullable @Size(max = 2000) String decisionNote,
        @Nullable Long newExpirationAt,
        @Nullable @Size(min = 1, max = 2000) String playerMessage,
        @Nullable UUID onBehalfOf
) {

    public AppealReviewDTO(AppealStatus decision, @Nullable String decisionNote, @Nullable Long newExpirationAt, @Nullable String playerMessage) {
        this(decision, decisionNote, newExpirationAt, playerMessage, null);
    }

    /** A review without a message to the player. */
    public static AppealReviewDTO withoutMessage(AppealStatus decision, @Nullable String decisionNote, @Nullable Long newExpirationAt) {
        return new AppealReviewDTO(decision, decisionNote, newExpirationAt, null, null);
    }
}
