package net.onelitefeather.blackhole.backend.clientrule.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleHitDTO;

public interface ClientRuleHitApi {

    @Operation(
            summary = "Report a client rule hit",
            description = "Stores the hit without any client trait and counts it on the rule. For a TEMPLATE rule the backend applies the "
                    + "rule's template once while the player has no active punishment from that rule, and never for a bypassed player. "
                    + "Proxy tokens only.",
            operationId = "reportClientRuleHit",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "Hit recorded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Recorded.class)))
    @ApiResponse(responseCode = "400", description = "Malformed owner or server",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "No enabled rule has that key; nothing is stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Error.class)))
    @Post("/hit")
    HttpResponse<ClientRuleHitDTO> report(@Valid @Body ClientRuleHitDTO.CreateRequest request);

    @Operation(
            summary = "Hits of one rule",
            description = "The newest hits of a rule, newest first (at most 500, default 100). Hits expire after the retention period.",
            operationId = "listClientRuleHits",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "The hits",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Hits.class)))
    @ApiResponse(responseCode = "404", description = "Rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Error.class)))
    @Get("/{key}/hits")
    HttpResponse<ClientRuleHitDTO> ofRule(@PathVariable String key, @Nullable @QueryValue Integer limit);

    @Operation(
            summary = "Rule hits of one player",
            description = "The newest hits of a player over all rules, newest first (at most 500, default 100).",
            operationId = "listClientRuleHitsOfOwner",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "The hits",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Hits.class)))
    @ApiResponse(responseCode = "400", description = "owner is not a SHA-512 hash",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleHitDTO.Error.class)))
    @Get("/hits")
    HttpResponse<ClientRuleHitDTO> ofOwner(@QueryValue String owner, @Nullable @QueryValue Integer limit);
}
