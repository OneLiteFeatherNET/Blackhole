package net.onelitefeather.blackhole.backend.intel.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.intel.IntelAllowListService;
import net.onelitefeather.blackhole.backend.intel.IntelHitService;
import net.onelitefeather.blackhole.backend.intel.IntelPolicyService;
import net.onelitefeather.blackhole.backend.intel.dto.AllowListDTO;
import net.onelitefeather.blackhole.backend.intel.dto.HitDTO;
import net.onelitefeather.blackhole.backend.intel.dto.PolicyDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link IntelVpnApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/intel")
public class IntelVpnController implements IntelVpnApi {

    private final IntelPolicyService policies;
    private final IntelAllowListService allowList;
    private final IntelHitService hits;

    @Inject
    public IntelVpnController(IntelPolicyService policies, IntelAllowListService allowList, IntelHitService hits) {
        this.policies = policies;
        this.allowList = allowList;
        this.hits = hits;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<PolicyDTO> policies() {
        return HttpResponse.ok(this.policies.list());
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<PolicyDTO> updatePolicy(PolicyDTO.UpdateRequest request, Actor actor) {
        PolicyDTO result = this.policies.update(request, actor);
        if (result instanceof PolicyDTO.Error error) {
            return AuditHeaders.audited(HttpResponse.<PolicyDTO>status(error.kind().status()).body(error), actor);
        }
        return AuditHeaders.audited(HttpResponse.ok(result), actor);
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<AllowListDTO> allow(AllowListDTO.CreateRequest request, Actor actor) {
        AllowListDTO result = this.allowList.add(request, actor);
        if (result instanceof AllowListDTO.Error error) {
            return AuditHeaders.audited(HttpResponse.<AllowListDTO>status(error.kind().status()).body(error), actor);
        }
        return AuditHeaders.audited(HttpResponse.ok(result), actor);
    }

    /** VIEWER and above: the panel's read. The proxy is refused. */
    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<HitDTO> hits(String owner) {
        HitDTO result = this.hits.listForOwner(owner);
        if (result instanceof HitDTO.Error error) {
            return HttpResponse.<HitDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
