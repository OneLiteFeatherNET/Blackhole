package net.onelitefeather.blackhole.backend.playerresolver;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Optional;

/**
 * Mojang's official (unauthenticated, rate-limited) name-to-UUID API - a no-content 204 response
 * means "no such player", which Micronaut maps straight to {@link Optional#empty()} for an
 * {@code Optional<T>} return type.
 */
@Client(id = "mojang", value = "https://api.mojang.com")
public interface MojangApiClient {

    @Get("/users/profiles/minecraft/{username}")
    Optional<Profile> getProfile(String username);

    @Serdeable
    record Profile(String id, String name) {
    }
}
