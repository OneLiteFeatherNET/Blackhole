package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

@Produces
@Singleton
@Requires(classes = TenantAccessDeniedException.class)
public class TenantAccessDeniedExceptionHandler implements ExceptionHandler<TenantAccessDeniedException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, TenantAccessDeniedException exception) {
        return HttpResponse.status(HttpStatus.FORBIDDEN, exception.getMessage());
    }
}
