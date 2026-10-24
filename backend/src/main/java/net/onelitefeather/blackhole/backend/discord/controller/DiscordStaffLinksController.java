package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/** Part of the {@code /discord/**} facade (role {@code DISCORD} only): the live staff links for the channel access reconcile. */
@Version(ApiVersion.V1)
@Controller("/discord/staff-links")
public class DiscordStaffLinksController implements DiscordStaffLinksApi {

    private final DiscordLinkService service;

    @Inject
    public DiscordStaffLinksController(DiscordLinkService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordLinkDTO> list() {
        return HttpResponse.ok(this.service.staffLinks());
    }
}
