package net.onelitefeather.blackhole.backend.intel.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.intel.dto.AllowListDTO;
import net.onelitefeather.blackhole.backend.intel.dto.HitDTO;
import net.onelitefeather.blackhole.backend.intel.dto.PolicyDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface IntelVpnApi {

    @Operation(
            summary = "Read the login policy per VPN, hosting and proxy class",
            description = "One policy per class: ALLOW, FLAG or DENY. A class without a stored row is FLAG.",
            operationId = "getIntelPolicies",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "The policies",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyDTO.Listing.class)))
    @Get("/policy")
    HttpResponse<PolicyDTO> policies();

    @Operation(
            summary = "Set the login policy of one class",
            description = "Only an admin may set DENY. The change is audited with the previous value.",
            operationId = "setIntelPolicy",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "The policy is stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The class or the policy is not a known value",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_POLICY: a moderator asked for DENY",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyDTO.Error.class)))
    @Post("/policy")
    HttpResponse<PolicyDTO> updatePolicy(@Valid @Body PolicyDTO.UpdateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Exempt a player from the VPN and hosting policy",
            description = "A moderator may exempt for 1 to 90 days. Only an admin may leave durationDays out, which makes the "
                    + "exemption permanent. The reason is required.",
            operationId = "addIntelAllowListEntry",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "The exemption is stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AllowListDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The owner, the reason or the duration is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AllowListDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_DURATION: a moderator asked for a permanent exemption",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AllowListDTO.Error.class)))
    @Post("/allow-list")
    HttpResponse<AllowListDTO> allow(@Valid @Body AllowListDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List the VPN and hosting hits of a player",
            description = "The newest hits of one owner, at most 50. Shows the clear IP, so staff only; a proxy may not read it.",
            operationId = "listIntelHitsOfPlayer",
            tags = {"Intel"}
    )
    @ApiResponse(responseCode = "200", description = "The hits",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = HitDTO.Page.class)))
    @ApiResponse(responseCode = "400", description = "The owner is not a SHA-512 hash",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = HitDTO.Error.class)))
    @Get("/players/{owner}/hits")
    HttpResponse<HitDTO> hits(@PathVariable String owner);
}
