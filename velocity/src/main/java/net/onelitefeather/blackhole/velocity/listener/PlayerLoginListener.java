package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.velocity.component.PunishmentTemplateComponent;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Listens for the PlayerFinishConfigurationEvent to disconnect players with active bans.
 * @since 1.0.0
 * @version 1.0.0
 * @author theEvilReaper
 */
public final class PlayerLoginListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerLoginListener.class);
    private final PunishProfileApi punishProfileApi;
    private final BlackholeConfig config;

    @Inject
    public PlayerLoginListener(@NotNull ApiClient apiClient, @NotNull BlackholeConfig config) {
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.config = config;
    }

    /**
     * Handles the logic to disconnect a player if they have an active ban
     * @param event the event
     */
    @Subscribe
    public void onLogin(@NotNull LoginEvent event) {
        Player player = event.getPlayer();

        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());
        Optional<PunishProfileDTO> profileOptional;
        try {
            profileOptional = Optional.of(this.punishProfileApi.getById(this.config.getTenantId(), uuidHash));
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for player {}: {}", player.getUsername(), e.getMessage());
            return;
        }

        PunishProfileDTO punishProfile = profileOptional.get();

        if (punishProfile.getActiveBan() == null) return;
        var activeBanDTO = punishProfile.getActiveBan();
        var templateDTO = activeBanDTO.getTemplate();
        if (templateDTO.getType() != PunishType.NETWORK) return;

        event.getPlayer().disconnect(PunishmentTemplateComponent.of(templateDTO, punishProfile));
    }
}
