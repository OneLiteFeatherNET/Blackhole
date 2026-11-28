package net.onelitefeather.blackhole.backend.firewall.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.firewall.FeedService;

import java.net.InetSocketAddress;

/**
 * Routing and OpenAPI annotations live on {@link FeedApi}, which this class implements. Anonymous at the role layer: the
 * token in the path and the source allow-list are checked by {@link FeedService}.
 */
@Version(ApiVersion.V1)
@Controller("/firewall/feed")
public class FeedController implements FeedApi {

    private static final String NO_CACHE = "no-cache";

    private final FeedService service;

    @Inject
    public FeedController(FeedService service) {
        this.service = service;
    }

    @Override
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<String> feed(String token, String feed, boolean comments, HttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        FeedService.Answer answer = this.service.serve(token, feed, comments,
                request.getHeaders().get(HttpHeaders.IF_NONE_MATCH),
                remote == null ? null : remote.getAddress());
        return switch (answer.outcome()) {
            case OK -> HttpResponse.ok(answer.body())
                    .contentType(MediaType.TEXT_PLAIN_TYPE)
                    .header(HttpHeaders.ETAG, answer.etag())
                    .header(HttpHeaders.CACHE_CONTROL, NO_CACHE);
            case NOT_MODIFIED -> HttpResponse.<String>status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, answer.etag())
                    .header(HttpHeaders.CACHE_CONTROL, NO_CACHE);
            case NOT_FOUND -> HttpResponse.<String>notFound().header(HttpHeaders.CACHE_CONTROL, NO_CACHE);
            case UNAVAILABLE -> HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.CACHE_CONTROL, NO_CACHE);
        };
    }
}
