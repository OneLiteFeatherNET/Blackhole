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
import net.onelitefeather.blackhole.backend.problem.ProblemMapper;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLeakCheckDTO;
import net.onelitefeather.blackhole.backend.discord.leak.LeakCheckService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Part of the {@code /discord/**} facade (role {@code DISCORD} only). Thin: one service call. The OpenAPI annotation sits on the
 * {@link DiscordLeakCheckApi} interface this class implements.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/discord/leak-check")
public class DiscordLeakCheckController implements DiscordLeakCheckApi {

    private final LeakCheckService service;

    @Inject
    public DiscordLeakCheckController(LeakCheckService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordLeakCheckDTO> check(DiscordLeakCheckDTO.Request request, Actor actor) {
        DiscordLeakCheckDTO result = this.service.check(request, actor);
        if (result instanceof DiscordLeakCheckDTO.Error error) {
            return ProblemMapper.fromKind(error, error.kind(), ProblemMapper.instance()).response();
        }
        return HttpResponse.ok(result);
    }
}
