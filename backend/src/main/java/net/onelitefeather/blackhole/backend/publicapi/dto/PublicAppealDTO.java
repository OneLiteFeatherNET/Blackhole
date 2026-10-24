package net.onelitefeather.blackhole.backend.publicapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/** Wire contract of filing and reading the player's appeal. The internal decision note is never part of it. */
@Schema(description = "The player's appeal")
@Serdeable
public sealed interface PublicAppealDTO {

    /** How an appeal was decided. */
    @Serdeable
    enum Decision {
        DENIED, GRANTED_LIFT, GRANTED_REDUCTION
    }

    /**
     * Files the appeal of the session's punishment.
     *
     * @param statement         1 to 2000 characters, plain text, without a UUID, an IP address or a Ban ID
     * @param conditionsVersion the version of the appeal conditions the player confirmed; must be the current one, else 409 {@code CONDITIONS_CHANGED}
     */
    @Schema(name = "PublicAppealRequest")
    @Serdeable
    @ReflectiveAccess
    record FileRequest(@Nullable String statement, @Nullable Integer conditionsVersion) implements PublicAppealDTO {
    }

    /**
     * The appeal conditions a player confirms before filing.
     *
     * @param version the number to send back as {@code conditionsVersion}
     * @param text    plain text; a blank line separates paragraphs and a line starting with "- " is a bullet
     */
    @Schema(name = "PublicAppealConditions")
    @Serdeable
    @ReflectiveAccess
    record Conditions(int version, @NonNull String text) implements PublicAppealDTO {
    }

    /**
     * The state of the appeal.
     *
     * @param status       {@code SUBMITTED}, {@code ELIGIBLE_PENDING_REVIEW}, {@code IN_REVIEW}, {@code GRANTED_LIFT},
     *                     {@code GRANTED_REDUCTION} or {@code DENIED}
     * @param decision     null until decided
     * @param newExpiresAt the new end of a shortened punishment, an ISO instant
     * @param filedAt      an ISO instant
     */
    @Schema(name = "PublicAppeal")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Status(@NonNull String status, @Nullable Decision decision, @Nullable String newExpiresAt, @Nullable String filedAt) implements PublicAppealDTO {
    }

    @Schema(name = "PublicAppealError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @Nullable String availableFrom, @NonNull PublicErrorKind kind) implements PublicAppealDTO, PublicError {
    }
}
