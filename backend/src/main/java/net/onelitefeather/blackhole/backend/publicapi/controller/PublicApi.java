package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicAppealDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicConversationDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicPunishmentDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicSessionDTO;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

/**
 * The public API of the appeal portal. Only the portal's backend-for-frontend calls it: it logs a player in
 * with the Ban ID, then sends the session token as {@code Authorization: Bearer} and the client address as
 * {@code X-Forwarded-For} (which this API neither reads nor stores). A staff or proxy token is not accepted
 * here, and a session token is accepted nowhere else. Every route works on the session's own punishment; none
 * takes a punishment, appeal or case identifier. Every answer is {@code Cache-Control: no-store}. Rate limiting
 * of the login and the write routes belongs to the edge (see the portal deployment notes), not to this API.
 */
public interface PublicApi {

    @Operation(
            summary = "Log in with the Ban ID",
            description = "Creates a player session for the Ban ID in the body. A wrong, unknown, malformed or not yet issued Ban ID all "
                    + "answer 401 INVALID_BAN_ID with the same body. A Ban ID that staff marked leaked logs in with scope LIMITED. The token "
                    + "is returned once and only its hash is stored.",
            operationId = "publicLogin",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The session",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicSessionDTO.Created.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_BAN_ID, always the same",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Post("/session")
    HttpResponse<?> login(@Nullable @Body PublicSessionDTO.LoginRequest request);

    @Operation(
            summary = "Read the session",
            description = "The CSRF value, the absolute expiry and the scope of the session behind the token.",
            operationId = "publicSession",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The session",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicSessionDTO.Current.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @Get("/session")
    HttpResponse<?> session(@Parameter(hidden = true) PlayerSession session);

    @Operation(
            summary = "Log out",
            description = "Deletes the session; the token stops working at once.",
            operationId = "publicLogout",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "204", description = "The session is gone")
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @Delete("/session")
    HttpResponse<?> logout(@Parameter(hidden = true) PlayerSession session);

    @Operation(
            summary = "The facts of the punishment",
            description = "An allow-list: reference, type, reason, section, times, state, evidence count and the appeal summary. Never an "
                    + "owner hash, a staff name, an ELO value, a note or evidence content. Works for every scope.",
            operationId = "publicPunishment",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The facts",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicPunishmentDTO.Facts.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @Get("/punishment")
    HttpResponse<?> punishment(@Parameter(hidden = true) PlayerSession session);

    @Operation(
            summary = "File the appeal",
            description = "Files the appeal with a statement of 1 to 2000 characters through the eligibility checklist. An appeal that is not "
                    + "eligible is not recorded: 409 NOT_ELIGIBLE (with availableFrom when the player only has to wait), FORFEITED or FROZEN "
                    + "after a leak. conditionsVersion must be the current version of the appeal conditions, else 409 CONDITIONS_CHANGED. "
                    + "At most one appeal is open.",
            operationId = "publicFileAppeal",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The filed appeal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicAppealDTO.Status.class)))
    @ApiResponse(responseCode = "400", description = "The statement is empty, too long or has control characters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "409", description = "FORFEITED, FROZEN, NOT_ELIGIBLE or CONDITIONS_CHANGED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "422", description = "The statement looks like a UUID, an IP address or a Ban ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Post("/appeal")
    HttpResponse<?> fileAppeal(@Parameter(hidden = true) PlayerSession session, @Nullable @Body PublicAppealDTO.FileRequest request);

    @Operation(
            summary = "The appeal conditions",
            description = "The current appeal conditions: a version number and a plain text. The player confirms them before filing and sends the "
                    + "version back as conditionsVersion; a changed version answers 409 CONDITIONS_CHANGED. Any session scope may read them.",
            operationId = "publicAppealConditions",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The current conditions",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicAppealDTO.Conditions.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @Get("/appeal/conditions")
    HttpResponse<?> appealConditions(@Parameter(hidden = true) PlayerSession session);

    @Operation(
            summary = "The appeal",
            description = "The open appeal, else the latest: status, decision and, for a shortened punishment, the new expiry. The internal "
                    + "decision note is never returned. Not for a leaked Ban ID (403).",
            operationId = "publicAppeal",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The appeal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicAppealDTO.Status.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "409", description = "NO_APPEAL",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Get("/appeal")
    HttpResponse<?> appeal(@Parameter(hidden = true) PlayerSession session);

    @Operation(
            summary = "The conversation",
            description = "The entries the player may see, after the seq cursor: player-visible messages (edits folded into the current text, "
                    + "deleted ones marked removed) and the markers APPEAL_FILED, APPEAL_DECIDED, FROZEN and FORFEITED. No staff names and no "
                    + "staff-only entries. Not for a leaked Ban ID (403).",
            operationId = "publicConversation",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "A page of entries",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicConversationDTO.Page.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Get("/conversation")
    HttpResponse<?> conversation(@Parameter(hidden = true) PlayerSession session, @Nullable @QueryValue Long cursor);

    @Operation(
            summary = "The appeal protocol",
            description = "The player's own copy of the conversation of a decided appeal, rendered on demand from the same player-visible "
                    + "entries as the conversation (you, the moderation team, the system; no staff names, no staff-only entries), as a text or "
                    + "JSON attachment. Not for a leaked Ban ID (403); 409 NOT_DECIDED while no appeal is decided.",
            operationId = "publicAppealProtocol",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The file (Content-Disposition: attachment, Cache-Control: no-store)",
            content = {
                    @Content(mediaType = "text/plain", schema = @Schema(type = "string")),
                    @Content(mediaType = "application/json", schema = @Schema(type = "string"))
            })
    @ApiResponse(responseCode = "400", description = "Unknown format",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "409", description = "NOT_DECIDED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Get("/appeal/protocol")
    HttpResponse<?> appealProtocol(@Parameter(hidden = true) PlayerSession session, @Nullable @QueryValue String format);

    @Operation(
            summary = "Post a message",
            description = "Adds a message of 1 to 2000 characters to the open appeal. The optional Idempotency-Key makes a double submit a no-op. "
                    + "409 NO_APPEAL (nothing is open) or FROZEN (the Ban ID is marked leaked).",
            operationId = "publicPostMessage",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The message was stored (repeated: true when the key had posted it before)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicConversationDTO.Posted.class)))
    @ApiResponse(responseCode = "400", description = "The text is empty, too long or has control characters, or the key is malformed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "409", description = "NO_APPEAL or FROZEN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "422", description = "The text looks like a UUID, an IP address or a Ban ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Post("/messages")
    HttpResponse<?> postMessage(
            @Parameter(hidden = true) PlayerSession session,
            @Nullable @Body PublicConversationDTO.PostRequest request,
            @Nullable @Header("Idempotency-Key") String idempotencyKey);
}
