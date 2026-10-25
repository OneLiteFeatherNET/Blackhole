package net.onelitefeather.blackhole.backend.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code GET /inbox}: what needs the staff team's attention. The response holds four
 * sections, each with its full count and at most ten items, oldest first. Expected failures answer
 * the {@link Error} variant.
 */
@Schema(description = "What needs the staff team's attention: four sections, each with a count and its oldest items")
@Serdeable
public sealed interface InboxDTO {

    /**
     * One item of a section. Identifiers are those of the appeal, the report or the punishment; a player is
     * only ever the hash of the owner.
     */
    @Schema(name = "InboxItem", description = "One item of an inbox section")
    @Serdeable
    @ReflectiveAccess
    record Item(
            @NonNull @Schema(description = "Identifier of the appeal, the report or the punishment") String id,
            @Nullable @Schema(description = "Short reference of the punishment, when there is one") String reference,
            @Nullable @Schema(description = "Hashed owner of the punishment, or the hashed reported player of a report") String owner,
            @Schema(description = "Epoch millis: when the item came in, or for an answer, when the player last wrote")
            long since
    ) {
    }

    @Schema(name = "InboxSection", description = "One section: the full count and at most ten items, oldest first")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Section(
            @Schema(description = "How many items the section has, not capped") long count,
            @NonNull List<Item> items
    ) {
    }

    @Schema(name = "InboxResponse", description = "The four sections of the inbox")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Response(
            @Schema(description = "Epoch millis at which the inbox was read") long generatedAt,
            @NonNull @Schema(description = "Appeal chats whose last visible message is from the player") Section needsAnswer,
            @NonNull @Schema(description = "Pending appeals without a decision") Section openAppeals,
            @NonNull @Schema(description = "Reports that are still open") Section newReports,
            @NonNull @Schema(description = "Open cases without an assignee") Section unassignedCases
    ) implements InboxDTO {
    }

    @Schema(name = "InboxError", description = "The inbox could not be read")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull ErrorKind kind, @NonNull String errorMessage) implements InboxDTO, ErrorResponse {
    }
}
