package net.onelitefeather.blackhole.backend.playerresolver.controller;

import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.playerresolver.dto.PlayerResolveDTO;
import net.onelitefeather.blackhole.backend.playerresolver.service.PlayerLookupService;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

/**
 * Resolving a name calls Mojang/Otis/NameMC through blocking clients, which must not run on the
 * Netty event loop - hence {@link ExecuteOn} on the blocking executor.
 */
@Version(ApiVersion.V1)
@Controller("/player")
@ExecuteOn(TaskExecutors.BLOCKING)
public class PlayerLookupController implements PlayerLookupApi {

    private final PlayerLookupService playerLookupService;

    @Inject
    public PlayerLookupController(PlayerLookupService playerLookupService) {
        this.playerLookupService = playerLookupService;
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<PlayerResolveDTO> resolve(String name) {
        PlayerResolveDTO result = this.playerLookupService.resolve(name);
        if (result instanceof PlayerResolveDTO.Error) {
            return HttpResponse.notFound(result);
        }
        return HttpResponse.ok(result);
    }
}
