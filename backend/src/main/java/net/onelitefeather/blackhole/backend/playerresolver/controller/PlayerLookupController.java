package net.onelitefeather.blackhole.backend.playerresolver.controller;

import net.onelitefeather.blackhole.backend.playerresolver.dto.PlayerResolveDTO;
import net.onelitefeather.blackhole.backend.playerresolver.service.PlayerLookupService;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

@Version(ApiVersion.V1)
@Controller("/player")
public class PlayerLookupController implements PlayerLookupApi {

    private final PlayerLookupService playerLookupService;

    @Inject
    public PlayerLookupController(PlayerLookupService playerLookupService) {
        this.playerLookupService = playerLookupService;
    }

    @Override
    public HttpResponse<PlayerResolveDTO> resolve(String name) {
        PlayerResolveDTO result = this.playerLookupService.resolve(name);
        if (result instanceof PlayerResolveDTO.Error) {
            return HttpResponse.notFound(result);
        }
        return HttpResponse.ok(result);
    }
}
