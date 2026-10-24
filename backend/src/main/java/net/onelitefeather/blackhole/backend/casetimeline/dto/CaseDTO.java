package net.onelitefeather.blackhole.backend.casetimeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CasePriority;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.casetimeline.NoteRedactionReason;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire contract of the case endpoints. Cases are staff-only. The player is identified by the short id
 * and a hash prefix only; no raw UUID or IP address appears in any variant.
 */
@Schema(description = "The case of a punishment: metadata, notes and timeline")
@Serdeable
public sealed interface CaseDTO {

    /**
     * Changes the staff metadata. Every field but {@code expectedVersion} is optional; a field that is
     * absent or equals the current value changes nothing.
     *
     * @param expectedVersion the {@code version} the caller read; a newer case answers 409
     * @param assigneeOid     the staff member to assign: a known human {@code staff_actor}
     * @param clearAssignee   removes the assignee (an absent {@code assigneeOid} cannot say that)
     * @param tags            the full new tag list: at most 10 slugs of {@code [a-z0-9-]{1,32}}
     */
    @Schema(name = "CaseUpdateRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(
            @NonNull Long expectedVersion,
            @Nullable CaseStatus status,
            @Nullable UUID assigneeOid,
            boolean clearAssignee,
            @Nullable CasePriority priority,
            @Nullable List<String> tags
    ) implements CaseDTO {
    }

    /**
     * Adds or edits a note. Any visibility in the body is ignored: notes are staff only.
     *
     * @param text 1 to 4000 characters, without a UUID or IP address
     */
    @Schema(name = "CaseNoteRequest")
    @Serdeable
    @ReflectiveAccess
    record NoteRequest(@NonNull String text) implements CaseDTO {
    }

    /**
     * Redacts every version of a note.
     *
     * @param reason why, as a code
     */
    @Schema(name = "CaseRedactRequest")
    @Serdeable
    @ReflectiveAccess
    record RedactRequest(@NonNull NoteRedactionReason reason) implements CaseDTO {
    }

    /** Another punishment of the same player. */
    @Schema(name = "CaseRelated")
    @Serdeable
    @ReflectiveAccess
    record Related(
            @NonNull String identifier,
            @NonNull String shortId,
            @Nullable String section,
            @NonNull String punishmentState,
            @NonNull String caseStatus,
            long createdAt
    ) {
    }

    /**
     * A case.
     *
     * @param ownerPrefix     the first characters of the player's hash, never the whole hash
     * @param punishmentState {@code ACTIVE}, {@code EXPIRED} or {@code LIFTED} (revoked or lifted by an appeal)
     * @param assigneeName    the assignee's display name; null when none or anonymized
     * @param version         the optimistic version to send as {@code expectedVersion}; 0 for a case that has no row yet
     * @param related         up to 20 other punishments of the same player, newest first
     * @param banIdLeakedAt   when the Ban ID of the current generation was marked leaked; null when it was not (a reissue clears it)
     * @param banIdLeakReason the leak reason code; null when not leaked
     * @param playerSeenSeq   the highest seq the player's conversation read has returned (0 when none); staff show "Seen" under the last
     *                        staff message at or below it. A number only, never a time.
     */
    @Schema(name = "CaseResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String punishmentIdentifier,
            @NonNull String shortId,
            @NonNull String ownerPrefix,
            @Nullable String section,
            @NonNull String punishmentState,
            boolean permanent,
            long punishmentCreatedAt,
            @NonNull CaseStatus status,
            @Nullable UUID assigneeOid,
            @Nullable String assigneeName,
            @NonNull CasePriority priority,
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<String> tags,
            long version,
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<Related> related,
            @Nullable Long banIdLeakedAt,
            @Nullable String banIdLeakReason,
            long playerSeenSeq
    ) implements CaseDTO {
    }

    /**
     * Who caused an event.
     *
     * @param oid   the person, if known
     * @param name  the display name from the staff actor; null when unknown or anonymized
     * @param trust {@code VERIFIED}, {@code UNVERIFIED}, {@code SYSTEM} or {@code PLAYER}; an unverified actor must not be shown as verified, and a player never carries a badge
     */
    @Schema(name = "CaseActorInfo")
    @Serdeable
    @ReflectiveAccess
    record ActorInfo(@Nullable UUID oid, @Nullable String name, @NonNull ActorTrust trust) {
    }

    /**
     * One timeline event.
     *
     * @param seq       the order within the case and part of the cursor
     * @param type      the dotted type such as {@code note.added}; unknown types are returned as they are
     * @param payload   the type's body; empty after a redaction
     * @param blocks    for {@code message.added} and {@code message.edited}: the sanitised block tree parsed from the Markdown source in
     *                  {@code payload.text} (same nodes as the public conversation entry); null for every other event and after a redaction
     * @param redactedAt set when a note version was redacted
     */
    @Schema(name = "CaseTimelineItem")
    @Serdeable
    @ReflectiveAccess
    record TimelineItem(
            long seq,
            @NonNull UUID identifier,
            @NonNull String type,
            int payloadVersion,
            long occurredAt,
            @NonNull CaseEventOrigin origin,
            @NonNull String visibility,
            @NonNull ActorInfo actor,
            @Nullable String subjectId,
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, Object> payload,
            @Nullable List<Map<String, Object>> blocks,
            @Nullable Long redactedAt
    ) {
    }

    /**
     * A page of the timeline.
     *
     * @param nextCursor pass it as {@code cursor} for the next page; null on the last page
     */
    @Schema(name = "CaseTimelinePage")
    @Serdeable
    @ReflectiveAccess
    record TimelinePage(
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<TimelineItem> items,
            @Nullable String nextCursor
    ) implements CaseDTO {
    }

    /** One row of the case list. */
    @Schema(name = "CaseListItem")
    @Serdeable
    @ReflectiveAccess
    record ListItem(
            @NonNull String punishmentIdentifier,
            @NonNull String shortId,
            @Nullable String section,
            @NonNull String punishmentState,
            @NonNull CaseStatus status,
            @Nullable UUID assigneeOid,
            @Nullable String assigneeName,
            @NonNull CasePriority priority,
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<String> tags,
            long punishmentCreatedAt,
            long lastActivityAt
    ) {
    }

    /** One page of the case list. */
    @Schema(name = "CaseListPage")
    @Serdeable
    @ReflectiveAccess
    record ListPage(
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<ListItem> items,
            int page,
            int size,
            long totalSize,
            int totalPages
    ) implements CaseDTO {
    }

    /**
     * The result of adding or editing a note.
     *
     * @param noteId the id of the note; the same across its edits
     * @param seq    the sequence number of the event that was appended
     */
    @Schema(name = "CaseNoteResponse")
    @Serdeable
    @ReflectiveAccess
    record NoteResponse(@NonNull String noteId, long seq) implements CaseDTO {
    }

    /** The result of a redaction. */
    @Schema(name = "CaseRedactResponse")
    @Serdeable
    @ReflectiveAccess
    record RedactResponse(@NonNull String noteId, int redactedVersions) implements CaseDTO {
    }

    @Schema(name = "CaseError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull CaseErrorKind kind) implements CaseDTO, ErrorResponse {
    }
}
