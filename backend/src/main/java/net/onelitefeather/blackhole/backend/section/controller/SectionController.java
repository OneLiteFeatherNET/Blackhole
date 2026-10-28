package net.onelitefeather.blackhole.backend.section.controller;

import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.security.Roles;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.util.List;

/**
 * Routing and OpenAPI annotations live on {@link SectionApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/section")
public class SectionController implements SectionApi {

    private final SectionService sectionService;

    @Inject
    public SectionController(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<SectionDTO> create(SectionDTO.CreateRequest request, Actor actor) {
        return AuditHeaders.audited(toResponse(this.sectionService.create(request, actor)), actor);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<SectionDTO> update(SectionDTO.UpdateRequest request, Actor actor) {
        return AuditHeaders.audited(toResponse(this.sectionService.update(request, actor)), actor);
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<List<SectionDTO.Response>> list() {
        return HttpResponse.ok(this.sectionService.list());
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<SectionDTO> get(String key) {
        return toResponse(this.sectionService.get(key));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<SectionDTO> disable(String key, Actor actor) {
        return AuditHeaders.audited(toResponse(this.sectionService.disable(key, actor)), actor);
    }

    private static MutableHttpResponse<SectionDTO> toResponse(SectionDTO result) {
        if (result instanceof SectionDTO.Error error) {
            return HttpResponse.<SectionDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
