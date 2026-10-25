package net.onelitefeather.blackhole.backend.staffprofile.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/**
 * Wire contract of {@code /staff-profile/me}: the signed-in staff member's display name and avatar choice. The
 * response never carries a UUID, an object id or the sign-in name; {@code avatarKey} is the keyed hash of the head.
 */
@Schema(description = "A staff member's display name and avatar choice")
@Serdeable
public sealed interface StaffProfileDTO {

    /**
     * The profile to save. A name is required; a save without the notice acknowledgement is refused unless it was
     * acknowledged before.
     *
     * @param displayName        the name players see, 2 to 32 characters
     * @param avatarMode         {@code AUTO} or {@code INITIALS}; {@code null} keeps the current choice
     * @param noticeAcknowledged whether the staff notice was read and accepted
     */
    @Schema(name = "StaffProfileUpdateRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(@Nullable String displayName, @Nullable String avatarMode, @Nullable Boolean noticeAcknowledged)
            implements StaffProfileDTO {
    }

    /**
     * The saved profile.
     *
     * @param displayName        the name, {@code null} while none is set
     * @param avatarMode         {@code AUTO} or {@code INITIALS}
     * @param avatarKey          the key of the head, {@code null} for initials
     * @param noticeAcknowledged whether the staff notice was accepted
     */
    @Schema(name = "StaffProfileResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@Nullable String displayName, @NonNull String avatarMode, @Nullable String avatarKey,
                    boolean noticeAcknowledged) implements StaffProfileDTO {
    }

    @Schema(name = "StaffProfileError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements StaffProfileDTO, ErrorResponse {
    }
}
