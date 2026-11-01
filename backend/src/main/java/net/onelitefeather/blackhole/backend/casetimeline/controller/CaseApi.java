package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.casetimeline.CasePriority;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.List;
import java.util.UUID;

/**
 * Case endpoints. A case is addressed by the punishment's identifier or short id. The acting staff
 * member is always the caller of the verified token; nothing in a body names an actor.
 */
public interface CaseApi {

    @Operation(
            summary = "List cases",
            description = "Lists cases, newest activity first. Every filter is optional. punishmentState is ACTIVE, EXPIRED or LIFTED.",
            operationId = "listCases",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "One page of cases",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.ListPage.class)))
    @ApiResponse(responseCode = "400", description = "A filter is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Get("/")
    HttpResponse<CaseDTO> list(
            @Nullable @QueryValue CaseStatus status,
            @Nullable @QueryValue UUID assignee,
            @Nullable @QueryValue String tag,
            @Nullable @QueryValue CasePriority priority,
            @Nullable @QueryValue String section,
            @Nullable @QueryValue String punishmentState,
            @Nullable @QueryValue Integer page,
            @Nullable @QueryValue Integer size,
            @Nullable @QueryValue String owner
    );

    @Operation(
            summary = "List players with cases",
            description = "One group per player that has a case matching the filters, newest activity first. openCount counts the "
                    + "cases whose status is not CLOSED, totalCount all matching cases. Paging is over players. Expand a group with "
                    + "GET /case?owner=<owner>. The filters are the same as the case list.",
            operationId = "listCaseGroups",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "One page of groups",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.GroupPage.class)))
    @ApiResponse(responseCode = "400", description = "A filter is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Get("/groups")
    HttpResponse<CaseDTO> groups(
            @Nullable @QueryValue CaseStatus status,
            @Nullable @QueryValue UUID assignee,
            @Nullable @QueryValue String tag,
            @Nullable @QueryValue CasePriority priority,
            @Nullable @QueryValue String section,
            @Nullable @QueryValue String punishmentState,
            @Nullable @QueryValue Integer page,
            @Nullable @QueryValue Integer size
    );

    @Operation(
            summary = "Get a case",
            description = "The case of a punishment with its metadata and up to 20 other punishments of the same player. A punishment "
                    + "without a case row reads as the default case (version 0); a read never creates one.",
            operationId = "getCase",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The case",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments; the message lists them",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Get("/{reference}")
    HttpResponse<CaseDTO> get(@PathVariable String reference);

    @Operation(
            summary = "Read the timeline of a case",
            description = "Events in timeline order (occurredAt, then seq), 50 per page by default and at most 200. Follow nextCursor "
                    + "for the next page. type may repeat and takes exact types or the groups note, appeal, evidence, case and punishment.",
            operationId = "getCaseTimeline",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "One page of events",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.TimelinePage.class)))
    @ApiResponse(responseCode = "400", description = "A filter or the cursor is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Get("/{reference}/timeline")
    HttpResponse<CaseDTO> timeline(
            @PathVariable String reference,
            @Nullable @QueryValue String cursor,
            @Nullable @QueryValue Integer size,
            @Nullable @QueryValue List<String> type,
            @Nullable @QueryValue String actorTrust,
            @Nullable @QueryValue Long from,
            @Nullable @QueryValue Long to,
            @Nullable @QueryValue String order
    );

    @Operation(
            summary = "Change a case",
            description = "Sets status, assignee, priority and tags. expectedVersion must be the version that was read; a value equal to "
                    + "the current one changes nothing and appends no event. Each change appends its event in the same statement.",
            operationId = "updateCase",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The case after the change",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid tags or no expectedVersion",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The case was changed since expectedVersion, or the short id is ambiguous",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "The assignee is not a known human staff member",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Patch("/{reference}")
    HttpResponse<CaseDTO> update(@PathVariable String reference, @Body CaseDTO.UpdateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Add a note",
            description = "Adds an internal staff note of 1 to 4000 characters. It must not contain a UUID or an IP address.",
            operationId = "addCaseNote",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The note was added",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.NoteResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid text",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Post("/{reference}/notes")
    HttpResponse<CaseDTO> addNote(@PathVariable String reference, @Body CaseDTO.NoteRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Edit a note",
            description = "Appends a new version of the note; the earlier versions stay. Only the verified author or an admin may edit.",
            operationId = "editCaseNote",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The note was edited",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.NoteResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid text",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "The caller is neither the author nor an admin",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or note",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The note was redacted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Put("/{reference}/notes/{noteId}")
    HttpResponse<CaseDTO> editNote(
            @PathVariable String reference, @PathVariable UUID noteId, @Body CaseDTO.NoteRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Redact a note",
            description = "Removes the text of every version of a note for data protection, keeping type, time and actor, and appends a "
                    + "note.redacted event. The reason is a code. Repeating it is harmless.",
            operationId = "redactCaseNote",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The note was redacted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.RedactResponse.class)))
    @ApiResponse(responseCode = "400", description = "No reason",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or note",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseDTO.Error.class)))
    @Post("/{reference}/notes/{noteId}/redact")
    HttpResponse<CaseDTO> redactNote(
            @PathVariable String reference, @PathVariable UUID noteId, @Body CaseDTO.RedactRequest request, @Parameter(hidden = true) Actor actor);
}
