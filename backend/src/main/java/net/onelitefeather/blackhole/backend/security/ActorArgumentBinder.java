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
 * Lets a controller method declare an {@link Actor} parameter: the verified caller of the request,
 * resolved from the validated token (never from the request body or query), so controllers stay
 * thin and no client can name the actor.
 */
@Singleton
public class ActorArgumentBinder implements TypedRequestArgumentBinder<Actor> {

    private final ActorResolver resolver;

    public ActorArgumentBinder(ActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Argument<Actor> argumentType() {
        return Argument.of(Actor.class);
    }

    @Override
    public BindingResult<Actor> bind(ArgumentConversionContext<Actor> context, HttpRequest<?> source) {
        Optional<Authentication> authentication = source.getUserPrincipal(Authentication.class);
        if (authentication.isEmpty()) {
            return ArgumentBinder.BindingResult.unsatisfied();
        }
        return () -> Optional.of(this.resolver.resolve(authentication.get()));
    }
}
