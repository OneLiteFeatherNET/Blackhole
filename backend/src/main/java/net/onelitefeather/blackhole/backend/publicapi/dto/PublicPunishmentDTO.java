package net.onelitefeather.blackhole.backend.publicapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The public facts of the session's punishment: an allow-list, never the entity. There is no owner hash,
 * no short id, no staff name or id, no ELO value, no note and no evidence content.
 */
@Schema(description = "The facts of the player's own punishment")
@Serdeable
public sealed interface PublicPunishmentDTO {

    /** Where the punishment stands. */
    @Serdeable
    enum State {
        ACTIVE, EXPIRED, LIFTED, REVOKED
    }

    /** Where the player's right to appeal stands. */
    @Serdeable
    enum AppealState {
        /** The minimum waiting time has not passed. */
        NOT_YET,
        /** The player may file now. */
        AVAILABLE,
        /** An appeal is open. */
        OPEN,
        /** The right is used up or the punishment is over. */
        DECIDED,
        /** A repeat appeal has to wait. */
        COOLDOWN,
        /** The Ban ID was marked leaked before any appeal: the right is gone. */
        FORFEITED,
        /** The Ban ID was marked leaked while an appeal is open: the player side can no longer write. */
        FROZEN
    }

    /**
     * @param availableFrom when filing opens, an ISO instant; only for {@code NOT_YET} and {@code COOLDOWN}
     */
    @Schema(name = "PublicAppealSummary")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record AppealSummary(@NonNull AppealState state, @Nullable String availableFrom) {
    }

    /**
     * @param reference     the pseudonymous label of the case (the punishment identifier), what the player may quote to the team
     * @param type          the kind of enforcement of the section, such as {@code BAN} or {@code MUTE}
     * @param reason        the reason text of the template
     * @param section       the display name of the section
     * @param createdAt     an ISO instant
     * @param expiresAt     an ISO instant, null for a permanent punishment
     * @param evidenceCount how many valid evidence items the team holds; the count only
     */
    @Schema(name = "PublicPunishment")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Facts(
            @NonNull String reference, @NonNull String type, @NonNull String reason, @NonNull String section, @NonNull String createdAt,
            boolean permanent, @Nullable String expiresAt, @NonNull State state, long evidenceCount, @NonNull AppealSummary appeal
    ) implements PublicPunishmentDTO {
    }

    @Schema(name = "PublicPunishmentError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @Nullable String availableFrom, @NonNull PublicErrorKind kind) implements PublicPunishmentDTO, PublicError {
    }
}
