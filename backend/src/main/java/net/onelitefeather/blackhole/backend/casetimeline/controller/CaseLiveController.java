package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseLiveService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.live.LiveStreams;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link CaseLiveApi}. {@code VIEWER} and above; the proxy, the Discord bot and players have no
 * rule here and are denied. Opening a stream reads the database, so it runs on the blocking executor.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class CaseLiveController implements CaseLiveApi {

    private final CaseLiveService live;

    @Inject
    public CaseLiveController(CaseLiveService live) {
        this.live = live;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<?> events(String reference, @Nullable String lastEventId) {
        return switch (this.live.open(reference, lastEventId)) {
            case LiveStreams.Opening.Opened opened -> HttpResponse.ok(opened.events())
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-store, no-transform")
                    .header("X-Accel-Buffering", "no");
            case LiveStreams.Opening.NotFound notFound -> HttpResponse.notFound();
            case LiveStreams.Opening.Forbidden forbidden -> HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN);
            default -> HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE);
        };
    }
}
