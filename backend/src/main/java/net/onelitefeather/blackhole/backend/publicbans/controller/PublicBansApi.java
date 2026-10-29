package net.onelitefeather.blackhole.backend.publicbans.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * The public ban list for partner networks and the website. Every route needs a named client's HTTP Basic credential over
 * HTTPS (see {@code BanListAccessFilter}); no Entra token is accepted here. Every answer is {@code Cache-Control: private, no-store}.
 */
public interface PublicBansApi {

    @Get
    @Operation(operationId = "publicBanList", summary = "List the public bans",
            description = "One keyset page of the listed sanctions. Entries carry the player hash, never a raw identifier.",
            security = @SecurityRequirement(name = "basicAuth"))
    HttpResponse<?> list(@Nullable @QueryValue String limit, @Nullable @QueryValue String cursor, @Nullable @QueryValue String kind,
                         @Nullable @QueryValue String status, @Nullable @QueryValue String playerHash, HttpRequest<?> request);

    @Get("/summary")
    @Operation(operationId = "publicBanSummary", summary = "Count the public bans per reason",
            description = "Counts per public reason and kind. Counts below the configured minimum are suppressed.",
            security = @SecurityRequirement(name = "basicAuth"))
    HttpResponse<?> summary(HttpRequest<?> request);

    @Get("/{hash}")
    @Operation(operationId = "publicBanEntry", summary = "Read one public ban",
            description = "One listed sanction by its entry hash. Unknown, malformed and expired-out-of-window hashes are not found.",
            security = @SecurityRequirement(name = "basicAuth"))
    HttpResponse<?> entry(@PathVariable String hash, HttpRequest<?> request);
}
