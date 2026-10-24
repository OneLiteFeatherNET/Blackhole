package net.onelitefeather.blackhole.backend.security;

import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * Lets a public controller method declare a {@link PlayerSession} parameter: the session of the validated
 * token, never a value from the request body, path or query.
 */
@Singleton
public class PlayerSessionArgumentBinder implements TypedRequestArgumentBinder<PlayerSession> {

    @Override
    public Argument<PlayerSession> argumentType() {
        return Argument.of(PlayerSession.class);
    }

    @Override
    public BindingResult<PlayerSession> bind(ArgumentConversionContext<PlayerSession> context, HttpRequest<?> source) {
        Optional<PlayerSession> session = source.getUserPrincipal(Authentication.class).flatMap(PlayerSession::of);
        if (session.isEmpty()) {
            return ArgumentBinder.BindingResult.unsatisfied();
        }
        return () -> session;
    }
}
