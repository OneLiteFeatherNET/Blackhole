package net.onelitefeather.blackhole.backend.report.controller;

import net.onelitefeather.blackhole.backend.security.Actor;
import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.report.dto.ReportRequestDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import net.onelitefeather.blackhole.backend.report.dto.ReportErrorDTO;
import net.onelitefeather.blackhole.backend.report.service.ReportService;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.core.version.annotation.Version;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.report.dto.ReportDTO;

import java.util.UUID;

/**
 * Player reports. Submission is rate-limited per {@code reporterHash} - otherwise reporting
 * itself becomes a griefing vector. Resolution can optionally apply a punishment template to the
 * reported player, the report system's actual integration point with punishments rather than a
 * parallel/separate mechanism. All of that logic lives in {@link ReportService}; this controller
 * only parses/validates the request, delegates, and maps the result to an {@code HttpResponse}.
 * Routing and OpenAPI annotations live on {@link ReportApi}, which this class implements.
 *
 * <p><b>Security note:</b> every route needs an Entra ID bearer token and an explicit
 * {@code @Secured} role (submit: MODERATOR or PROXY; list: VIEWER; resolve: MODERATOR). The actor
 * comes from the token. {@code reporterHash} is still client-supplied and does not identify the
 * reporter, so a per-reporterHash limit can be bypassed by varying it; the network-wide limit in
 * {@link ReportService} is the backstop.</p>
 */
@Version(ApiVersion.V1)
@Controller("/report")
public class ReportController implements ReportApi {

    private final ReportService reportService;

    @Inject
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.PROXY})
    public HttpResponse<?> submit(ReportRequestDTO submission) {
        ReportService.SubmitResult result = this.reportService.submit(submission);
        return switch (result) {
            case ReportService.SubmitResult.Success success -> HttpResponse.ok(success.report());
            case ReportService.SubmitResult.RateLimited ignored -> HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
        };
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<Page<ReportDTO>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.reportService.getAll(pageable));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<?> resolve(UUID identifier, ReportResolutionDTO resolution, Actor actor) {
        ReportService.ResolveResult result = this.reportService.resolve(identifier, resolution, actor);
        return switch (result) {
            case ReportService.ResolveResult.Success success -> HttpResponse.ok(success.report());
            case ReportService.ResolveResult.NotFound ignored ->
                    error(ErrorKind.NOT_FOUND, "there is no report with this identifier");
            case ReportService.ResolveResult.AlreadyResolved already ->
                    error(ErrorKind.CONFLICT, "the report is already resolved (" + already.status() + ")");
            case ReportService.ResolveResult.TemplateNotAllowed notAllowed ->
                    error(ErrorKind.VALIDATION, notAllowed.message());
            case ReportService.ResolveResult.PunishmentRejected rejected -> error(rejected.kind(), rejected.message());
        };
    }

    private static HttpResponse<?> error(ErrorKind kind, String message) {
        return HttpResponse.status(kind.status()).body(new ReportErrorDTO(message, kind));
    }
}
