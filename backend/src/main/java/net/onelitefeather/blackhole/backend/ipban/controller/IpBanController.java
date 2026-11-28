package net.onelitefeather.blackhole.backend.ipban.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.ipban.IpBanService;
import net.onelitefeather.blackhole.backend.ipban.dto.IpBanDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link IpBanApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/ipban")
public class IpBanController implements IpBanApi {

    private final IpBanService service;

    @Inject
    public IpBanController(IpBanService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<IpBanDTO> create(IpBanDTO.CreateRequest request, Actor actor) {
        return switch (this.service.create(request, actor)) {
            case IpBanDTO.Error error -> HttpResponse.<IpBanDTO>status(error.kind().status()).body(error);
            case IpBanDTO written -> HttpResponse.created(written);
        };
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<IpBanDTO> list(int limit) {
        return HttpResponse.ok(this.service.list(limit));
    }

    /** The proxy's list. VIEWER and MODERATOR are refused: the list is for the proxies only. */
    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<IpBanDTO> active() {
        return HttpResponse.ok(this.service.active());
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<IpBanDTO> get(String id) {
        return respond(this.service.find(id));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<IpBanDTO> lift(String id, IpBanDTO.LiftRequest request, Actor actor) {
        return respond(this.service.lift(id, request, actor));
    }

    private static HttpResponse<IpBanDTO> respond(IpBanDTO result) {
        if (result instanceof IpBanDTO.Error error) {
            return HttpResponse.<IpBanDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
