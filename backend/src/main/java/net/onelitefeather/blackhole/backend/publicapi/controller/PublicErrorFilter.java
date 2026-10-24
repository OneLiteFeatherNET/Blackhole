package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.publicapi.service.PlayerSessionService;

/**
 * Makes the framework's own 400 on a public route (a body that is not readable JSON, a wrong type, no body) say
 * nothing about the input: on the login it is the same 401 {@code INVALID_BAN_ID} as every other failed login (a
 * format failure is never a 400 there), elsewhere a plain {@code VALIDATION} body. It works on the response, so it
 * does not depend on which exception the framework raised, and it never echoes the request.
 */
@ServerFilter("/public/**")
public class PublicErrorFilter {

    private static final String LOGIN_PATH = "/public/session";

    @ResponseFilter
    public void hideFrameworkErrors(HttpRequest<?> request, MutableHttpResponse<?> response) {
        if (response.status() != HttpStatus.BAD_REQUEST || response.body() instanceof PublicErrorBody) {
            return;
        }
        boolean login = request.getMethod() == HttpMethod.POST && LOGIN_PATH.equals(request.getPath());
        if (login) {
            response.status(HttpStatus.UNAUTHORIZED);
            response.body(PublicErrorBody.of(PlayerSessionService.invalidBanId()));
        } else {
            response.body(new PublicErrorBody("VALIDATION", null));
        }
        response.header("Cache-Control", "no-store");
    }
}
