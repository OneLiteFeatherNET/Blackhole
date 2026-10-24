package net.onelitefeather.blackhole.backend.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.validator.TokenValidator;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.publicapi.service.PlayerSessionService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Accepts the opaque token of a player session next to the JWT validator. A token without the session prefix
 * is not looked up at all (staff tokens never touch the session table); one with it is valid only while its
 * session is live. The look-up also slides the idle limit.
 */
@Singleton
public class PlayerSessionTokenValidator implements TokenValidator<HttpRequest<?>> {

    private final PlayerSessionService sessions;

    public PlayerSessionTokenValidator(PlayerSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public Publisher<Authentication> validateToken(String token, HttpRequest<?> request) {
        if (token == null || !token.startsWith(PlayerSessionRule.TOKEN_PREFIX)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> this.sessions.authenticate(token).map(PlayerSession::toAuthentication).orElse(null))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
