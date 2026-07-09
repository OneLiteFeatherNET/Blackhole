package net.onelitefeather.blackhole.backend.playerresolver;

import net.onelitefeather.blackhole.backend.playerresolver.service.PlayerResolverService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Fallback resolver: Mojang's official name-to-UUID API. Tried only once Otis has already come
 * back empty (see {@link PlayerResolverService}).
 */
@Singleton
@Order(20)
@Requires(property = "blackhole.player-resolver.mojang.enabled", value = "true", defaultValue = "true")
public class MojangPlayerResolver implements PlayerResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MojangPlayerResolver.class);

    private final MojangApiClient client;

    public MojangPlayerResolver(MojangApiClient client) {
        this.client = client;
    }

    @Override
    public Optional<ResolvedPlayer> resolve(String name) {
        try {
            return this.client.getProfile(name)
                    .map(profile -> new ResolvedPlayer(dashedUuid(profile.id()), profile.name(), "mojang"));
        } catch (HttpClientResponseException e) {
            if (e.getStatus().getCode() != 404) {
                LOGGER.warn("Mojang lookup failed for player {}: {}", name, e.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Mojang returns the UUID without dashes; {@link UUID#fromString(String)} requires them.
     */
    private static UUID dashedUuid(String undashed) {
        return UUID.fromString(undashed.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5"
        ));
    }
}
