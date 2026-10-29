package net.onelitefeather.blackhole.backend.firewall.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * The firewall pull feeds. Hidden from the OpenAPI document and the generated client: the firewall is the only caller, and
 * the token in the path is never a value a Java client sends. Routing lives here; the controller implements it.
 */
public interface FeedApi {

    @Hidden
    @Get("/{token}/{feed}")
    HttpResponse<String> feed(@PathVariable String token, @PathVariable String feed,
                              @QueryValue(defaultValue = "false") boolean comments,
                              HttpRequest<?> request);
}
