package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.authentication.DefaultAuthorizationExceptionHandler;
import io.micronaut.security.authentication.WwwAuthenticateChallengeProvider;
import io.micronaut.security.config.RedirectConfiguration;
import io.micronaut.security.config.RedirectService;
import io.micronaut.security.errors.PriorToLoginPersistence;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

/**
 * Labels the 403 of a caller whose Entra token lost its group list (more than 200 groups) with the
 * reason {@code groups-overage}, so a client can tell this apart from a plain missing role. Every
 * other authorization failure keeps the library's response.
 */
@Singleton
@Replaces(DefaultAuthorizationExceptionHandler.class)
public class GroupsOverageExceptionHandler extends DefaultAuthorizationExceptionHandler {

    public static final String REASON = "groups-overage";

    private final BlackholeRolesFinder rolesFinder;

    @Inject
    public GroupsOverageExceptionHandler(
            ErrorResponseProcessor<?> errorResponseProcessor,
            RedirectConfiguration redirectConfiguration,
            RedirectService redirectService,
            @Nullable List<WwwAuthenticateChallengeProvider<HttpRequest<?>>> challengeProviders,
            @Nullable PriorToLoginPersistence priorToLoginPersistence,
            BlackholeRolesFinder rolesFinder
    ) {
        super(errorResponseProcessor, redirectConfiguration, redirectService, challengeProviders, priorToLoginPersistence);
        this.rolesFinder = rolesFinder;
    }

    @Override
    public MutableHttpResponse<?> handle(HttpRequest request, AuthorizationException exception) {
        Authentication authentication = exception.getAuthentication();
        if (exception.isForbidden() && authentication != null && this.rolesFinder.isGroupsOverage(authentication.getAttributes())) {
            return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("reason", REASON));
        }
        return super.handle(request, exception);
    }
}
