package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.live.LiveStreams;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicLiveService;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.blackhole.backend.security.PlayerSessionRule;

/**
 * Routing and OpenAPI annotations live on {@link PublicLiveApi}. Opening a stream reads the database, so it runs on the blocking executor;
 * the stream itself is written by the live hub.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/public")
public class PublicLiveController implements PublicLiveApi {

    private final PublicLiveService live;

    @Inject
    public PublicLiveController(PublicLiveService live) {
        this.live = live;
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> events(PlayerSession session, @Nullable String lastEventId) {
        return switch (this.live.open(session, lastEventId)) {
            case LiveStreams.Opening.Opened opened -> HttpResponse.ok(opened.events())
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-store, no-transform")
                    .header("X-Accel-Buffering", "no");
            case LiveStreams.Opening.Forbidden forbidden -> HttpResponse.status(PublicErrorKind.FORBIDDEN.status())
                    .body(new PublicErrorBody("FORBIDDEN", null)).header("Cache-Control", "no-store");
            default -> HttpResponse.status(PublicErrorKind.UNAVAILABLE.status())
                    .body(new PublicErrorBody("UNAVAILABLE", null)).header("Cache-Control", "no-store");
        };
    }
}
