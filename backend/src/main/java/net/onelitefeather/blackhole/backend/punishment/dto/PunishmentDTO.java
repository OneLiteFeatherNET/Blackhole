package net.onelitefeather.blackhole.backend.punishment.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of {@code /punishment/apply} and {@code /punishment/revoke}.
 */
@Schema(description = "Applying and revoking section punishments")
@Serdeable
public sealed interface PunishmentDTO {

    /**
     * Apply a template to a player. Exactly one of {@code templateKey} and {@code templateId} is required.
     * The staff member is the caller of the verified token; there is no client-supplied source.
     *
     * @param onBehalfOf            the Minecraft UUID of the in-game staff member the proxy acts for; accepted only
     *                              from the {@code PROXY} role (HTTP 400 otherwise)
     * @param evidenceCorrelationId the key evidence was sent under before this punishment existed;
     *                              the pending evidence of this owner with that key is attached to the new punishment
     * @param scope                 where a {@code KICK} step takes the player from; {@code NETWORK} when absent.
     *                              Ignored by a step that punishes
     * @param target                the server name or section key for a {@code SERVER} or {@code SECTION} scope
     * @param warningOnly           {@code true} for {@code /warn}: the step the ladder reached must be a {@code WARN} step,
     *                              otherwise nothing is applied (HTTP 422 {@code WARN_STEP_REQUIRED})
     */
    @Schema(name = "PunishmentApplyRequest")
    @Serdeable
    @ReflectiveAccess
    record ApplyRequest(
            @NonNull @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            @Nullable String templateKey,
            @Nullable UUID templateId,
            @Nullable UUID onBehalfOf,
            @Nullable @Size(max = 64) String evidenceCorrelationId,
            @Nullable KickScope scope,
            @Nullable String target,
            @Nullable Boolean warningOnly,
            @Nullable @Size(max = 64) String playerUuid
    ) implements PunishmentDTO {

        /** An apply request without a player UUID (the caller did not know it). */
        public ApplyRequest(String owner, String templateKey, UUID templateId, UUID onBehalfOf, String evidenceCorrelationId,
                            KickScope scope, String target, Boolean warningOnly) {
            this(owner, templateKey, templateId, onBehalfOf, evidenceCorrelationId, scope, target, warningOnly, null);
        }

        /** An apply request that claims no pending evidence. */
        public ApplyRequest(String owner, String templateKey, UUID templateId, UUID onBehalfOf) {
            this(owner, templateKey, templateId, onBehalfOf, null, null, null, null);
        }

        /** An apply request that names no kick scope: a kick step kicks from the network. */
        public ApplyRequest(String owner, String templateKey, UUID templateId, UUID onBehalfOf, String evidenceCorrelationId) {
            this(owner, templateKey, templateId, onBehalfOf, evidenceCorrelationId, null, null, null);
        }

        /** An apply request that takes whatever step the ladder reached. */
        public ApplyRequest(String owner, String templateKey, UUID templateId, UUID onBehalfOf, String evidenceCorrelationId,
                            KickScope scope, String target) {
            this(owner, templateKey, templateId, onBehalfOf, evidenceCorrelationId, scope, target, null);
        }
    }

    /**
     * The result of applying a template.
     *
     * @param punishment  the created temporary punishment
     * @param section     the section it belongs to
     * @param enforcement how the section is enforced
     * @param step        the escalation step that was applied, starting at 1
     * @param stepCount   how many steps the template has
     * @param scoreBefore the section score before the ELO cost
     * @param scoreAfter  the section score after the ELO cost
     * @param escalation  the permanent punishment the cost caused, if any
     */
    @Schema(name = "PunishmentApplyResponse")
    @Serdeable
    @ReflectiveAccess
    record ApplyResponse(
            @NonNull PunishEntryDTO punishment,
            @NonNull String section,
            @NonNull EnforcementKind enforcement,
            int step,
            int stepCount,
            int scoreBefore,
            int scoreAfter,
            @Nullable Escalation escalation
    ) implements PunishmentDTO {
    }

    /**
     * What applying a template would do right now, without doing it: the escalation step the server
     * selects, its cost and whether the cost would push the player into a permanent punishment.
     *
     * @param section              the section the template belongs to
     * @param enforcement          how the section is enforced
     * @param step                 the escalation step that would apply, starting at 1
     * @param stepCount            how many steps the template has
     * @param duration             the ISO-8601 duration of the step; absent for a kick step
     * @param eloCost              the ELO the step would deduct
     * @param scoreBefore          the section score now
     * @param scoreAfter           the section score after the deduction
     * @param permanentThreshold   the section's permanent threshold
     * @param escalatesToPermanent whether the deduction would leave the score below that threshold
     */
    @Schema(name = "PunishmentPreviewResponse")
    @Serdeable
    @ReflectiveAccess
    record PreviewResponse(
            @NonNull String section,
            @NonNull EnforcementKind enforcement,
            int step,
            int stepCount,
            @Nullable String duration,
            int eloCost,
            int scoreBefore,
            int scoreAfter,
            int permanentThreshold,
            boolean escalatesToPermanent
    ) implements PunishmentDTO {
    }

    /**
     * A permanent punishment the system applied because a deduction left the score too low.
     *
     * @param punishment     the permanent punishment, now the section's active one
     * @param resultingScore the section score that triggered it
     */
    @Schema(name = "PunishmentEscalation")
    @Serdeable
    @ReflectiveAccess
    record Escalation(@NonNull PunishEntryDTO punishment, int resultingScore) {
    }

    /**
     * Revoke the active punishment of a section. The revoking staff member is the caller of the verified token.
     *
     * @param onBehalfOf the Minecraft UUID of the in-game staff member the proxy acts for; accepted only from
     *                   the {@code PROXY} role (HTTP 400 otherwise)
     */
    @Schema(name = "PunishmentRevokeRequest")
    @Serdeable
    @ReflectiveAccess
    record RevokeRequest(
            @NonNull @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            @NonNull @NotBlank String section,
            @Nullable UUID onBehalfOf
    ) implements PunishmentDTO {
    }

    /**
     * The result of revoking a section's active punishment.
     *
     * @param punishment the revoked punishment, now in history
     * @param section    the section it belonged to
     * @param scoreReset the section score after a permanent punishment was pardoned, else {@code null}
     */
    @Schema(name = "PunishmentRevokeResponse")
    @Serdeable
    @ReflectiveAccess
    record RevokeResponse(
            @NonNull PunishEntryDTO punishment,
            @NonNull String section,
            @Nullable Integer scoreReset
    ) implements PunishmentDTO {
    }

    /** The punishments that match a short ID; several if short IDs collide, none if nothing matches. */
    /**
     * Staff look up a punishment by the Ban ID a player quotes.
     *
     * @param banId the Ban ID as typed, with or without {@code #}, in any case; a credential, so it
     *              is a body field and is never printed
     */
    @Schema(name = "PunishmentLookupRequest")
    @Serdeable
    @ReflectiveAccess
    record LookupRequest(@Nullable String banId) implements PunishmentDTO {

        /** Never prints the Ban ID. */
        @Override
        public String toString() {
            return "PunishmentDTO.LookupRequest[banId=***]";
        }
    }

    @Schema(name = "PunishmentLookupResponse")
    @Serdeable
    @ReflectiveAccess
    record LookupResponse(@NonNull PunishEntryDTO punishment) implements PunishmentDTO {
    }

    @Schema(name = "PunishmentShortIdMatches")
    @Serdeable
    @ReflectiveAccess
    record ShortIdMatches(@NonNull List<PunishEntryDTO> punishments) implements PunishmentDTO {
    }

    @Schema(name = "PunishmentError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements PunishmentDTO, ErrorResponse {
    }
}
