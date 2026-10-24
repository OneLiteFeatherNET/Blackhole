package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link DiscordLinkApi.Player}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/player-discord-link")
public class PlayerDiscordLinkController implements DiscordLinkApi.Player {

    private final DiscordLinkService service;

    @Inject
    public PlayerDiscordLinkController(DiscordLinkService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<DiscordLinkDTO> issueCode(DiscordLinkDTO.PlayerCodeRequest request, Actor actor) {
        return StaffDiscordLinkController.respond(this.service.issuePlayerCode(request, actor));
    }
}
