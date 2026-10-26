package net.onelitefeather.blackhole.backend.kick.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface KickApi {

    @Operation(
            summary = "Kick a player",
            description = "Takes one player off the network, a server or a section and records it as a KICK in the player's "
                    + "history. A kick is a moment, not a sanction: it is never active, cannot be undone, and counts as an "
                    + "earlier step for escalation. The request answers at once; nothing is delivered yet. The proxies are "
                    + "told over the event bus and report back, so poll GET /kick/{id} for PENDING, DELIVERED, NOT_ON_TARGET "
                    + "or NOT_ONLINE. The Idempotency-Key header is required and derives the kick: a repeat answers the same "
                    + "kick with repeated true and writes nothing. A player who is permanently banned in the target section "
                    + "is kicked all the same; the answer carries a warning and the ban is unchanged. The staff member is "
                    + "the caller of the token.",
            operationId = "kickPlayer",
            tags = {"Kick"}
    )
    @ApiResponse(responseCode = "202", description = "The kick was written and the proxies were told",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Accepted.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (owner must be a SHA-512 hash, reason 1 to 200 characters without a UUID, IP address or Ban ID, Idempotency-Key missing or malformed)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The target section is disabled, or the Idempotency-Key was already used for a different kick",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "KICK_TARGET_INVALID: a scoped kick without a target, a target that is no server name or section, or a target on a network kick",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @Post
    HttpResponse<KickDTO> kick(
            @Body KickDTO.CreateRequest request,
            @Header("Idempotency-Key") @Nullable @Parameter(name = "Idempotency-Key", description = "1 to 80 characters of letters, digits, '.', '_', ':' and '-'; required") String idempotencyKey,
            @Parameter(hidden = true) Actor actor
    );

    @Operation(
            summary = "Kick everybody from a server or section",
            description = "One audited admin action. It writes a single record and no punishment, case or ELO for any player. "
                    + "The proxies are told over the event bus; poll GET /kick/{id} for DELIVERED with deliveredCount, or "
                    + "NO_PROXY_ANSWER once the deadline passed. The Idempotency-Key header is required.",
            operationId = "kickMass",
            tags = {"Kick"}
    )
    @ApiResponse(responseCode = "202", description = "The action was written and the proxies were told",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Accepted.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (reason, Idempotency-Key)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The target section is disabled, or the Idempotency-Key was already used for a different action",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "KICK_TARGET_INVALID: the scope is not SERVER or SECTION, or the target is missing or unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @Post("/mass")
    HttpResponse<KickDTO> kickMass(
            @Body KickDTO.MassCreateRequest request,
            @Header("Idempotency-Key") @Nullable @Parameter(name = "Idempotency-Key", description = "1 to 80 characters of letters, digits, '.', '_', ':' and '-'; required") String idempotencyKey,
            @Parameter(hidden = true) Actor actor
    );

    @Operation(
            summary = "Get a kick and how it went",
            description = "The status is derived when read: PENDING before the deadline, DELIVERED as soon as a proxy "
                    + "reports a move, NOT_ON_TARGET when a proxy found the player but not on the target, NOT_ONLINE at or "
                    + "after the deadline when no proxy found the player. A mass kick is DELIVERED or, after the deadline "
                    + "with no answer, NO_PROXY_ANSWER. The proxy reads the reason here before it shows the screen.",
            operationId = "getKick",
            tags = {"Kick"}
    )
    @ApiResponse(responseCode = "200", description = "The kick",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "No such kick",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @Get("/{id}")
    HttpResponse<KickDTO> get(@PathVariable String id);

    @Operation(
            summary = "Report what a proxy did about a kick",
            description = "Called by a proxy after it acted on the kick event, or found nothing to act on. Idempotent per "
                    + "kick and proxy: answering again keeps one record. Only the proxy may call it.",
            operationId = "acknowledgeKick",
            tags = {"Kick"}
    )
    @ApiResponse(responseCode = "204", description = "Stored")
    @ApiResponse(responseCode = "400", description = "Invalid proxyId",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "No such kick",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = KickDTO.Error.class)))
    @Post("/{id}/deliveries")
    HttpResponse<KickDTO> acknowledge(@PathVariable String id, @Body KickDTO.Ack ack);
}
