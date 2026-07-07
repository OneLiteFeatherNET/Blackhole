package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ChatSignalDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.velocity.component.PunishmentTemplateComponent;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public final class PlayerChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerChatListener.class);

    private final PunishProfileApi punishProfileApi;
    private final EloApi eloApi;
    private final BlackholeConfig config;

    @Inject
    public PlayerChatListener(@NotNull ApiClient blackholeClient, @NotNull BlackholeConfig config) {
        this.punishProfileApi = new PunishProfileApi(blackholeClient);
        this.eloApi = new EloApi(blackholeClient);
        this.config = config;
    }

    @Subscribe
    public void onChat(@NotNull PlayerChatEvent event) {
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

        if (punishProfile.getActiveBan() != null && punishProfile.getActiveBan().getTemplate().getType() == PunishType.CHAT) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            player.sendMessage(PunishmentTemplateComponent.of(punishProfile.getActiveBan().getTemplate(), punishProfile));
            return;
        }

        submitChatSignal(player, uuidHash, event.getMessage());
    }

    /**
     * Scores the message for the dual-ELO chat track. Best-effort - a failure here must never
     * affect chat delivery, which has already been decided by the mute check above.
     */
    private void submitChatSignal(@NotNull Player player, @NotNull String uuidHash, @NotNull String message) {
        try {
            this.eloApi.submitChatSignal(new ChatSignalDTO().tenantId(this.config.getTenantId()).owner(uuidHash).message(message));
        } catch (ApiException e) {
            LOGGER.error("Failed to submit chat signal for ELO scoring for player {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
