package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLeakCheckDTO;
import net.onelitefeather.blackhole.backend.discord.leak.LeakCheckService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/** Routing and OpenAPI annotations of the leak check. */
public interface DiscordLeakCheckApi {

    @Operation(
            summary = "Verify Ban ID look-alikes found in a public channel",
            description = "Called by the Discord bot only. Candidates that are a current Ban ID mark the case leaked (reason POSTED_PUBLIC, source "
                    + "DISCORD_BOT, the poster as an opaque participant); the answer counts matches and never names a punishment. A candidate that is "
                    + "no Ban ID causes nothing and is not stored or logged.",
            operationId = "checkDiscordLeak",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "How many candidates matched",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLeakCheckDTO.Result.class)))
    @ApiResponse(responseCode = "400", description = "Malformed request",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordLeakCheckDTO.Error.class)))
    @Post
    HttpResponse<DiscordLeakCheckDTO> check(@Body DiscordLeakCheckDTO.Request request, @Parameter(hidden = true) Actor actor);
}
