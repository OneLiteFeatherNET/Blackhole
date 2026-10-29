package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link DiscordLinkApi.Staff}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/staff-discord-link")
public class StaffDiscordLinkController implements DiscordLinkApi.Staff {

    private final DiscordLinkService service;

    @Inject
    public StaffDiscordLinkController(DiscordLinkService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<DiscordLinkDTO> issueCode(Actor actor) {
        return respond(this.service.issueStaffCode(actor));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<DiscordLinkDTO> status(Actor actor) {
        return respond(this.service.staffStatus(actor));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<DiscordLinkDTO> unlink(Actor actor) {
        return respond(this.service.unlinkStaff(actor));
    }

    static MutableHttpResponse<DiscordLinkDTO> respond(DiscordLinkDTO result) {
        if (result instanceof DiscordLinkDTO.Error error) {
            return HttpResponse.<DiscordLinkDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
