package net.onelitefeather.blackhole.backend.playerresolver.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;

import java.util.Map;

/**
 * Stands in for api.mojang.com so the resolver test needs no real network. Only active when the
 * test switches {@code test.fake-mojang} on, so no other test's application context sees it.
 */
@Controller("/users/profiles/minecraft")
@Requires(property = "test.fake-mojang", value = "true")
class FakeMojangController {

    @Get("/{username}")
    Map<String, String> profile(@PathVariable String username) {
        return Map.of("id", "069a79f444e94726a5befca90e38aaf5", "name", username);
    }
}
