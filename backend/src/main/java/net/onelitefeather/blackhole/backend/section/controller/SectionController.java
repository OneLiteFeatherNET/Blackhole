package net.onelitefeather.blackhole.backend.section.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
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
    public HttpResponse<SectionDTO> create(SectionDTO.CreateRequest request) {
        return toResponse(this.sectionService.create(request));
    }

    @Override
    public HttpResponse<SectionDTO> update(SectionDTO.UpdateRequest request) {
        return toResponse(this.sectionService.update(request));
    }

    @Override
    public HttpResponse<List<SectionDTO.Response>> list() {
        return HttpResponse.ok(this.sectionService.list());
    }

    @Override
    public HttpResponse<SectionDTO> get(String key) {
        return toResponse(this.sectionService.get(key));
    }

    @Override
    public HttpResponse<SectionDTO> disable(String key) {
        return toResponse(this.sectionService.disable(key));
    }

    private static HttpResponse<SectionDTO> toResponse(SectionDTO result) {
        if (result instanceof SectionDTO.Error error) {
            return HttpResponse.<SectionDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
