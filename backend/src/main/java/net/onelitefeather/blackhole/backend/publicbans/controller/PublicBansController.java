package net.onelitefeather.blackhole.backend.publicbans.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.problem.ProblemDetail;
import net.onelitefeather.blackhole.backend.problem.ProblemMapper;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanRoutes;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO.PublicBanEntry;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO.PublicBanError;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO.PublicBanPage;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO.PublicBanSummary;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO.PublicBanSummaryRow;
import net.onelitefeather.blackhole.backend.publicbans.service.PublicBansService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP adapter of the public ban list: one service call per route, the answer mapped to a status and a body. The
 * Basic credential is checked before any of these methods runs (BanListAccessFilter), so the routes are marked anonymous
 * for Micronaut Security and rely on that filter.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/public/bans")
public class PublicBansController implements PublicBansApi {

    private final PublicBansService service;

    @Inject
    public PublicBansController(PublicBansService service) {
        this.service = service;
    }

    @Override
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> list(@Nullable String limit, @Nullable String cursor, @Nullable String kind, @Nullable String status,
                                @Nullable String playerHash, HttpRequest<?> request) {
        return respond(this.service.list(limit, cursor, kind, status, playerHash), request);
    }

    @Override
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> summary(HttpRequest<?> request) {
        return respond(this.service.summary(), request);
    }

    @Override
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> entry(String hash, HttpRequest<?> request) {
        return respond(this.service.entry(hash), request);
    }

    @Override
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> unrouted(String hash, String rest, HttpRequest<?> request) {
        return problem(new PublicBanError(404, "NOT_FOUND", "No ban-list route matches the request.", List.of()), request);
    }

    private static HttpResponse<?> respond(PublicBansDTO result, HttpRequest<?> request) {
        return switch (result) {
            case PublicBanEntry entry -> HttpResponse.ok(entry);
            case PublicBanPage page -> HttpResponse.ok(page);
            case PublicBanSummary summary -> HttpResponse.ok(summary);
            case PublicBanError error -> problem(error, request);
        };
    }

    private static HttpResponse<?> problem(PublicBanError error, HttpRequest<?> request) {
        Map<String, Object> extensions = new HashMap<>();
        extensions.put("code", error.code());
        if (!error.errors().isEmpty()) {
            extensions.put("errors", error.errors());
        }
        ProblemMapper.Mapped mapped = ProblemMapper.fromStatus(HttpStatus.valueOf(error.status()), error.detail(),
                PublicBanRoutes.template(request.getPath()), extensions);
        return HttpResponse.status(mapped.status()).body(mapped.body()).contentType(ProblemDetail.CONTENT_TYPE);
    }
}
