package net.onelitefeather.blackhole.backend.report.feedback.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.report.feedback.ReportFeedbackService;
import net.onelitefeather.blackhole.backend.report.feedback.dto.ReportFeedbackDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * The proxy's routes for telling reporters about the outcome of their reports. Only the {@code PROXY}
 * role may call them: the proxy asks for a reporter's messages when the reporter joins or is poked,
 * shows them and acknowledges them. Routing and OpenAPI annotations live on {@link ReportFeedbackApi}.
 */
@Version(ApiVersion.V1)
@Controller("/report")
public class ReportFeedbackController implements ReportFeedbackApi {

    private final ReportFeedbackService service;

    public ReportFeedbackController(ReportFeedbackService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<ReportFeedbackDTO.ListResponse> pending(String owner) {
        return HttpResponse.ok(this.service.pending(owner));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<Void> acknowledge(UUID identifier) {
        this.service.acknowledge(identifier);
        return HttpResponse.noContent();
    }
}
