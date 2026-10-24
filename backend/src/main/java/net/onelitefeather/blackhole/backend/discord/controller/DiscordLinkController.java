package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * The {@code /discord/**} facade of the Discord bot (role {@code DISCORD} only). Routing and OpenAPI
 * annotations live on {@link DiscordLinkApi.Bot}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/discord/link")
public class DiscordLinkController implements DiscordLinkApi.Bot {

    private final DiscordLinkService service;

    @Inject
    public DiscordLinkController(DiscordLinkService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordLinkDTO> confirm(DiscordLinkDTO.ConfirmRequest request) {
        return StaffDiscordLinkController.respond(this.service.confirm(request));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordLinkDTO> whoami(String discordUserId) {
        return StaffDiscordLinkController.respond(this.service.whoami(discordUserId));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordLinkDTO> unlink(String discordUserId) {
        return StaffDiscordLinkController.respond(this.service.unlinkDiscordUser(discordUserId));
    }
}
