package net.onelitefeather.blackhole.backend.clientrule.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleService;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleDTO;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.List;

/**
 * Routing and OpenAPI annotations live on {@link ClientRuleApi}, which this class implements.
 * Everyone on the team and the proxy may read; only admins write.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/client-rule")
public class ClientRuleController implements ClientRuleApi {

    private final ClientRuleService service;

    @Inject
    public ClientRuleController(ClientRuleService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ClientRuleDTO> create(ClientRuleDTO.CreateRequest request, Actor actor) {
        return toResponse(this.service.create(request, actor.oid()));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ClientRuleDTO> update(String key, ClientRuleDTO.UpdateRequest request, Actor actor) {
        return toResponse(this.service.update(key, request, actor.oid()));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ClientRuleDTO> delete(String key) {
        return toResponse(this.service.delete(key));
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<List<ClientRuleDTO.Response>> list() {
        return HttpResponse.ok(this.service.list());
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<ClientRuleDTO> get(String key) {
        return toResponse(this.service.get(key));
    }

    private static HttpResponse<ClientRuleDTO> toResponse(ClientRuleDTO result) {
        if (result instanceof ClientRuleDTO.Error error) {
            return HttpResponse.<ClientRuleDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
