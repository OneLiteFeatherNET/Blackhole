package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.SessionInfoDTO;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client brand (e.g. "vanilla", a mod/client identifier) arrives via a separate plugin
 * channel negotiation shortly after login, not at {@code LoginEvent} time itself - this is Phase
 * 7's dashboard enrichment, merged into the same session info as {@link PlayerLoginListener}'s
 * protocol-version capture.
 */
public final class PlayerClientBrandListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerClientBrandListener.class);
    private final PunishProfileApi punishProfileApi;

    @Inject
    public PlayerClientBrandListener(@NotNull ApiClient apiClient) {
        this.punishProfileApi = new PunishProfileApi(apiClient);
    }

    @Subscribe
    public void onClientBrand(@NotNull PlayerClientBrandEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());
        try {
            this.punishProfileApi.updateSessionInfo(uuidHash,
                    new SessionInfoDTO().owner(uuidHash).clientBrand(event.getBrand()));
        } catch (ApiException e) {
            LOGGER.debug("Client brand not recorded for {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
