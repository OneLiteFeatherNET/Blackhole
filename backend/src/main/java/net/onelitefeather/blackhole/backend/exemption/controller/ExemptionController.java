package net.onelitefeather.blackhole.backend.exemption.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.exemption.ExemptionReviewService;
import net.onelitefeather.blackhole.backend.exemption.ExemptionService;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link ExemptionApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/exemption")
public class ExemptionController implements ExemptionApi {

    private final ExemptionService exemptions;
    private final ExemptionReviewService reviews;

    @Inject
    public ExemptionController(ExemptionService exemptions, ExemptionReviewService reviews) {
        this.exemptions = exemptions;
        this.reviews = reviews;
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<ExemptionDTO> status(String owner) {
        return respond(this.exemptions.status(owner));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<ExemptionDTO> report(ExemptionDTO.ReportRequest request) {
        return respond(this.exemptions.report(request));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ExemptionDTO> reviews() {
        return respond(this.reviews.list());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ExemptionDTO> apply(UUID id, ExemptionDTO.DecisionRequest request, Actor actor) {
        return respond(this.reviews.decide(id, true, request.justification(), actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ExemptionDTO> dismiss(UUID id, ExemptionDTO.DecisionRequest request, Actor actor) {
        return respond(this.reviews.decide(id, false, request.justification(), actor));
    }

    static HttpResponse<ExemptionDTO> respond(ExemptionDTO result) {
        if (result instanceof ExemptionDTO.Error error) {
            return HttpResponse.<ExemptionDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
