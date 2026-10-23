package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/**
 * A route with no security rule at all, to prove the application rejects it. Only registered when a
 * test switches {@code test.unsecured-probe} on, so no other test's application sees it.
 */
@Controller("/test-unsecured-probe")
@Requires(property = "test.unsecured-probe", value = "true")
class UnsecuredProbeController {

    @Get
    String probe() {
        return "reached";
    }
}
