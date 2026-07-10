package net.onelitefeather.blackhole.backend.playerresolver;

import net.onelitefeather.blackhole.backend.playerresolver.service.PlayerResolverService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import net.onelitefeather.otis.client.api.PlayerApi;
import net.onelitefeather.otis.client.invoker.ApiClient;
import net.onelitefeather.otis.client.invoker.ApiException;
import net.onelitefeather.otis.client.model.OtisPlayerDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Resolves via Otis, the team's own in-house player master-data service - the primary, most
 * trustworthy resolver in the chain (see {@link PlayerResolverService}).
 */
@Singleton
@Order(10)
@Requires(property = "blackhole.player-resolver.otis.enabled", value = "true", defaultValue = "true")
@Requires(property = "blackhole.player-resolver.otis.base-url", pattern = ".+")
public class OtisPlayerResolver implements PlayerResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtisPlayerResolver.class);

    private final PlayerApi playerApi;

    public OtisPlayerResolver(@Value("${blackhole.player-resolver.otis.base-url}") String baseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(baseUrl);
        this.playerApi = new PlayerApi(apiClient);
    }

    @Override
    public Optional<ResolvedPlayer> resolve(String name) {
        try {
            OtisPlayerDTO player = this.playerApi.getPlayerByName(name);
            if (player == null || player.getPlayerUuid() == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedPlayer(player.getPlayerUuid(), player.getPlayerName(), "otis"));
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                LOGGER.warn("Otis lookup failed for player {}: {}", name, e.getMessage());
            }
            return Optional.empty();
        }
    }
}
