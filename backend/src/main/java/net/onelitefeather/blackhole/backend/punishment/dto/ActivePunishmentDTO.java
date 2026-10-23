package net.onelitefeather.blackhole.backend.punishment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.util.List;

/**
 * Wire contract of the active punishment overview. The player is identified by the hashed owner only:
 * no raw UUID, IP address or name is stored or returned, and the actor appears as a display name only.
 */
@Schema(description = "The overview of currently punished players")
@Serdeable
public sealed interface ActivePunishmentDTO {

    /**
     * One active punishment.
     *
     * @param owner          the player's SHA-512 owner hash; the key of the player page
     * @param punishmentId   the punishment's identifier, which addresses its case
     * @param shortId        the {@code #} and eight hex characters players quote
     * @param section        the section key
     * @param sectionName    the section's display name; null if the section was deleted
     * @param kind           how the section is enforced; null if the section was deleted
     * @param targetKind     what the section's server list names (relevant to server sections)
     * @param templateKey    the template the punishment came from; null for automatic punishments
     * @param reason         the template's reason or the stored reason; for an automatic punishment an English placeholder
     * @param automatic      whether the ELO system applied it
     * @param expiresAt      when it ends (epoch millis); null for a permanent punishment
     * @param caseStatus     the status of its case; null when it has no case row
     * @param actorName      the display name of the verified staff member who applied it; null when unverified, unknown, anonymized or automatic
     */
    @Schema(name = "ActivePunishmentItem")
    @Serdeable
    @ReflectiveAccess
    record Item(
            @NonNull String owner,
            @NonNull String punishmentId,
            @NonNull String shortId,
            @NonNull String section,
            @Nullable String sectionName,
            @Nullable EnforcementKind kind,
            @Nullable ServerTargetKind targetKind,
            @Nullable String templateKey,
            @Nullable String reason,
            boolean automatic,
            long createdAt,
            @Nullable Long expiresAt,
            boolean permanent,
            @Nullable CaseStatus caseStatus,
            @Nullable String actorName
    ) {
    }

    /** One page of the overview. */
    @Schema(name = "ActivePunishmentPage")
    @Serdeable
    @ReflectiveAccess
    record ListPage(
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<Item> items,
            int page,
            int size,
            long totalSize,
            int totalPages
    ) implements ActivePunishmentDTO {
    }

    @Schema(name = "ActivePunishmentError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ActivePunishmentDTO, ErrorResponse {
    }
}
