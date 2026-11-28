package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.security.Actor;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link DiscordLinkApi.Admin}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/admin/discord-link")
public class AdminDiscordLinkController implements DiscordLinkApi.Admin {

    private final DiscordLinkService service;

    @Inject
    public AdminDiscordLinkController(DiscordLinkService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<DiscordLinkDTO> list() {
        return StaffDiscordLinkController.respond(this.service.list());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<DiscordLinkDTO> remove(UUID id, Actor actor) {
        return AuditHeaders.audited(StaffDiscordLinkController.respond(this.service.adminRemove(id, actor)), actor);
    }
}
