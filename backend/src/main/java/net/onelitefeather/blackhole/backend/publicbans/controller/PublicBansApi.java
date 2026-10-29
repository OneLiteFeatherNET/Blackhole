package net.onelitefeather.blackhole.backend.publicbans.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO;

/**
 * The public ban list for partner networks and the website. Every route needs a named client's HTTP Basic credential over
 * HTTPS (see {@code BanListAccessFilter}); no Entra token is accepted here. Every answer is {@code Cache-Control: private, no-store}.
 */
public interface PublicBansApi {

    @Get
    @Operation(operationId = "publicBanList", summary = "List the public bans",
            description = "One keyset page of the listed sanctions. Entries carry the player hash, never a raw identifier.",
            security = @SecurityRequirement(name = "basicAuth"))
    @ApiResponse(responseCode = "200", description = "One page of entries",
            content = @Content(schema = @Schema(implementation = PublicBansDTO.PublicBanPage.class)))
    @ApiResponse(responseCode = "400", description = "A query parameter or the cursor is not valid", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    @ApiResponse(responseCode = "401", description = "Missing, malformed or wrong Basic credentials", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    @ApiResponse(responseCode = "403", description = "The request did not arrive over HTTPS", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    HttpResponse<?> list(@Nullable @QueryValue String limit, @Nullable @QueryValue String cursor, @Nullable @QueryValue String kind,
                         @Nullable @QueryValue String status, @Nullable @QueryValue String playerHash, HttpRequest<?> request);

    @Get("/summary")
    @Operation(operationId = "publicBanSummary", summary = "Count the public bans per reason",
            description = "Counts per public reason and kind. Counts below the configured minimum are suppressed.",
            security = @SecurityRequirement(name = "basicAuth"))
    @ApiResponse(responseCode = "200", description = "The counts of the window",
            content = @Content(schema = @Schema(implementation = PublicBansDTO.PublicBanSummary.class)))
    @ApiResponse(responseCode = "401", description = "Missing, malformed or wrong Basic credentials", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    @ApiResponse(responseCode = "403", description = "The request did not arrive over HTTPS", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    HttpResponse<?> summary(HttpRequest<?> request);

    @Get("/{hash}")
    @Operation(operationId = "publicBanEntry", summary = "Read one public ban",
            description = "One listed sanction by its entry hash. Unknown, malformed and expired-out-of-window hashes are not found.",
            security = @SecurityRequirement(name = "basicAuth"))
    @ApiResponse(responseCode = "200", description = "The entry",
            content = @Content(schema = @Schema(implementation = PublicBansDTO.PublicBanEntry.class)))
    @ApiResponse(responseCode = "401", description = "Missing, malformed or wrong Basic credentials", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    @ApiResponse(responseCode = "403", description = "The request did not arrive over HTTPS", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    @ApiResponse(responseCode = "404", description = "No entry matches the hash", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = PublicBansDTO.PublicBanError.class)))
    HttpResponse<?> entry(@PathVariable String hash, HttpRequest<?> request);

    /**
     * Catches the paths of two or more segments under the prefix, so the Basic chain and the no-store rule cover them too.
     * It stays out of the single-segment match of {@link #entry}, which would be ambiguous. Hidden from the OpenAPI document.
     */
    @Get("/{hash}/{rest:.+}")
    @Hidden
    HttpResponse<?> unrouted(@PathVariable String hash, @PathVariable String rest, HttpRequest<?> request);
}
