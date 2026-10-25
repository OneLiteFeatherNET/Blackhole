package net.onelitefeather.blackhole.backend.kick.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.kick.service.KickService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link KickApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/kick")
public class KickController implements KickApi {

    private final KickService kickService;

    /**
     * @param kickService kicks players and records what the proxies report
     */
    @Inject
    public KickController(KickService kickService) {
        this.kickService = kickService;
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<KickDTO> kick(KickDTO.CreateRequest request, String idempotencyKey, Actor actor) {
        return accepted(this.kickService.kick(request, idempotencyKey, actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<KickDTO> kickMass(KickDTO.MassCreateRequest request, String idempotencyKey, Actor actor) {
        return accepted(this.kickService.kickMass(request, idempotencyKey, actor));
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<KickDTO> get(String id) {
        return switch (this.kickService.find(id)) {
            case KickDTO.Error error -> HttpResponse.<KickDTO>status(error.kind().status()).body(error);
            case KickDTO found -> HttpResponse.ok(found);
        };
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<KickDTO> acknowledge(String id, KickDTO.Ack ack) {
        return switch (this.kickService.acknowledge(id, ack)) {
            case KickDTO.Error error -> HttpResponse.<KickDTO>status(error.kind().status()).body(error);
            case KickDTO stored -> HttpResponse.noContent();
        };
    }

    private static HttpResponse<KickDTO> accepted(KickDTO result) {
        return switch (result) {
            case KickDTO.Error error -> HttpResponse.<KickDTO>status(error.kind().status()).body(error);
            case KickDTO written -> HttpResponse.accepted().body(written);
        };
    }
}
