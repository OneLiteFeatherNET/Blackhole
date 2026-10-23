package net.onelitefeather.blackhole.backend.security.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of {@code /staff-link} and {@code /admin/staff-link}: linking a staff member's Entra
 * identity to their Minecraft account with a one-time code. The code appears in exactly one response
 * ({@link CodeResponse}), never in an error and never in an example.
 */
@Schema(description = "A staff member's link between their sign-in and their Minecraft account")
@Serdeable
public sealed interface StaffLinkDTO {

    /**
     * What the Velocity proxy sends to confirm a code.
     *
     * @param code       the code the player typed in game, with or without dash, in any case
     * @param onBehalfOf the Minecraft UUID of the player who typed it; required
     */
    @Schema(name = "StaffLinkConfirmRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(@Nullable String code, @Nullable UUID onBehalfOf) implements StaffLinkDTO {
    }

    /**
     * A freshly issued code.
     *
     * @param code      the code as shown to a person, {@code ABCD-EFGH}; only ever returned here
     * @param expiresAt when it stops working, epoch millis
     */
    @Schema(name = "StaffLinkCodeResponse")
    @Serdeable
    @ReflectiveAccess
    record CodeResponse(@NonNull String code, long expiresAt) implements StaffLinkDTO {
    }

    /**
     * The link a confirmation created.
     *
     * @param oid           the staff member
     * @param displayName   their name from the sign-in, if known
     * @param minecraftUuid the linked Minecraft account
     * @param linkedAt      when, epoch millis
     */
    @Schema(name = "StaffLinkConfirmResponse")
    @Serdeable
    @ReflectiveAccess
    record LinkedResponse(
            @NonNull UUID oid, @Nullable String displayName, @NonNull UUID minecraftUuid, long linkedAt
    ) implements StaffLinkDTO {
    }

    /**
     * The caller's own link status.
     *
     * @param linked        whether a Minecraft account is linked
     * @param minecraftUuid the linked account, only when linked
     * @param linkedAt      when it was linked, epoch millis, only when linked
     */
    @Schema(name = "StaffLinkStatusResponse")
    @Serdeable
    @ReflectiveAccess
    record StatusResponse(boolean linked, @Nullable UUID minecraftUuid, @Nullable Long linkedAt) implements StaffLinkDTO {
    }

    /**
     * Who a Minecraft account belongs to, for the in-game status command.
     *
     * @param linked      whether the account is linked
     * @param displayName the linked staff member's name, if known; absent once anonymized
     */
    @Schema(name = "StaffLinkLookupResponse")
    @Serdeable
    @ReflectiveAccess
    record LookupResponse(boolean linked, @Nullable String displayName) implements StaffLinkDTO {
    }

    /** One link in the admin list. */
    @Schema(name = "StaffLinkItem")
    @Serdeable
    @ReflectiveAccess
    record Item(@NonNull UUID oid, @Nullable String displayName, @NonNull UUID minecraftUuid, long linkedAt) {
    }

    /** All links, for admins. */
    @Schema(name = "StaffLinkListResponse")
    @Serdeable
    @ReflectiveAccess
    record ListResponse(@NonNull List<Item> items) implements StaffLinkDTO {
    }

    /** The staff member whose link was removed. */
    @Schema(name = "StaffLinkRemovedResponse")
    @Serdeable
    @ReflectiveAccess
    record RemovedResponse(@NonNull UUID oid) implements StaffLinkDTO {
    }

    @Schema(name = "StaffLinkError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements StaffLinkDTO, ErrorResponse {
    }
}
