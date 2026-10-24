package net.onelitefeather.blackhole.backend.clientrule.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleHitService;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleHitDTO;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link ClientRuleHitApi}, which this class implements.
 * The proxy reports; the team reads, and the proxy may not.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/client-rule")
public class ClientRuleHitController implements ClientRuleHitApi {

    private final ClientRuleHitService service;

    @Inject
    public ClientRuleHitController(ClientRuleHitService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<ClientRuleHitDTO> report(ClientRuleHitDTO.CreateRequest request) {
        return toResponse(this.service.record(request));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<ClientRuleHitDTO> ofRule(String key, @Nullable Integer limit) {
        return toResponse(this.service.ofRule(key, limit));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<ClientRuleHitDTO> ofOwner(String owner, @Nullable Integer limit) {
        return toResponse(this.service.ofOwner(owner, limit));
    }

    private static HttpResponse<ClientRuleHitDTO> toResponse(ClientRuleHitDTO result) {
        if (result instanceof ClientRuleHitDTO.Error error) {
            return HttpResponse.<ClientRuleHitDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
