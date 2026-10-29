package net.onelitefeather.blackhole.backend.publicbans.auth;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.problem.ProblemDetail;
import net.onelitefeather.blackhole.backend.problem.ProblemMapper;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * The Basic chain of the ban list: it covers exactly the three ban-list routes and runs before the controllers. It refuses
 * plain HTTP (403 {@code HTTPS_REQUIRED}, no challenge), then checks the credential (401 with the Basic challenge for every
 * failure cause). Every answer, including those of other routes under the prefix, is {@code no-store} and is attributed to
 * its client.
 */
@ServerFilter({BanListAccessFilter.ROOT, BanListAccessFilter.ROOT + "/**"})
public class BanListAccessFilter {

    static final String ROOT = "/public/bans";
    static final String CLIENT_ATTRIBUTE = "blackhole.ban-list.client";
    static final String REASON_ATTRIBUTE = "blackhole.ban-list.reason";
    static final String CHALLENGE = "Basic realm=\"blackhole-ban-list\", charset=\"UTF-8\"";
    static final String NO_STORE = "private, no-store";
    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private final BanListHttpsGuard https;
    private final BanListAuthenticator authenticator;
    private final BanListAttribution attribution;

    @Inject
    public BanListAccessFilter(BanListHttpsGuard https, BanListAuthenticator authenticator, BanListAttribution attribution) {
        this.https = https;
        this.authenticator = authenticator;
        this.attribution = attribution;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> admit(MutableHttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (!this.https.isSecure(request.getUri().getScheme(), request.getHeaders().get(FORWARDED_PROTO),
                remote == null ? null : remote.getAddress())) {
            request.setAttribute(REASON_ATTRIBUTE, "https-required");
            return refusal(HttpStatus.FORBIDDEN, "HTTPS_REQUIRED", "The ban list is served over HTTPS only.", request.getPath(), false);
        }
        BanListAuthenticator.Outcome outcome = this.authenticator.authenticate(request.getHeaders().get(HttpHeaders.AUTHORIZATION));
        if (outcome.client() == null) {
            request.setAttribute(REASON_ATTRIBUTE, reasonName(outcome.reason()));
            return refusal(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication with a ban-list client is required.",
                    request.getPath(), true);
        }
        request.setAttribute(CLIENT_ATTRIBUTE, outcome.client());
        return null;
    }

    @ResponseFilter
    public void record(HttpRequest<?> request, MutableHttpResponse<?> response) {
        response.header(HttpHeaders.CACHE_CONTROL, NO_STORE);
        String client = request.getAttribute(CLIENT_ATTRIBUTE, String.class).orElse(null);
        String reason = request.getAttribute(REASON_ATTRIBUTE, String.class).orElse(null);
        this.attribution.answered(client, reason, routeOf(request.getPath()), response.code());
    }

    private static HttpResponse<?> refusal(HttpStatus status, String code, String detail, String instance, boolean challenge) {
        ProblemMapper.Mapped mapped = ProblemMapper.fromStatus(status, detail, instance, Map.of("code", code));
        MutableHttpResponse<?> response = HttpResponse.status(status).body(mapped.body()).contentType(ProblemDetail.CONTENT_TYPE);
        response.header(HttpHeaders.CACHE_CONTROL, NO_STORE);
        if (challenge) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        }
        return response;
    }

    private static String reasonName(BanListAuthenticator.Reason reason) {
        return reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** The route class of a path: a bounded label for the metric, never the hash itself. */
    static String routeOf(String path) {
        if (path.equals(ROOT) || path.equals(ROOT + "/")) {
            return "list";
        }
        return path.endsWith("/summary") ? "summary" : "entry";
    }
}
