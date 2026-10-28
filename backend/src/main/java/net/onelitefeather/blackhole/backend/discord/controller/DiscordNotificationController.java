package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordNotificationService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordNotificationDTO;
import net.onelitefeather.blackhole.backend.problem.ProblemMapper;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Part of the {@code /discord/**} facade (role {@code DISCORD} only). Routing and OpenAPI annotations live
 * on {@link DiscordNotificationApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/discord/notifications")
public class DiscordNotificationController implements DiscordNotificationApi {

    private final DiscordNotificationService service;

    @Inject
    public DiscordNotificationController(DiscordNotificationService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordNotificationDTO> report(UUID identifier) {
        return respond(this.service.report(identifier));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordNotificationDTO> appeal(UUID identifier) {
        return respond(this.service.appeal(identifier));
    }

    private static HttpResponse<DiscordNotificationDTO> respond(DiscordNotificationDTO result) {
        if (result instanceof DiscordNotificationDTO.Error error) {
            return ProblemMapper.fromKind(error, error.kind(), ProblemMapper.instance()).response();
        }
        return HttpResponse.ok(result);
    }
}
