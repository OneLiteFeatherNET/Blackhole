package net.onelitefeather.blackhole.backend.audit.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.audit.AuditService;
import net.onelitefeather.blackhole.backend.audit.dto.AuditDTO;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link AuditApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/admin/audit")
public class AuditController implements AuditApi {

    private final AuditService auditService;

    @Inject
    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<AuditDTO> list(@Nullable Integer limit, @Nullable String cursor, @Nullable String action,
                                       @Nullable UUID actorOid, @Nullable String targetRef) {
        AuditDTO result = this.auditService.page(limit, cursor, action, actorOid, targetRef);
        if (result instanceof AuditDTO.Error error) {
            return HttpResponse.<AuditDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
