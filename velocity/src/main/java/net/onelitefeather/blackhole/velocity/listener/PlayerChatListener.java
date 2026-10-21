package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloChatSignalRequest;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Denies the chat messages of muted players (an active punishment in a MUTE section) and forwards
 * the others to the backend for toxicity scoring.
 */
public final class PlayerChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerChatListener.class);

    private final EloApi eloApi;
    private final PunishmentState state;
    private final PunishmentScreens screens;

    @Inject
    public PlayerChatListener(@NotNull ApiClient blackholeClient, @NotNull PunishmentState state, @NotNull PunishmentScreens screens) {
        this.eloApi = new EloApi(blackholeClient);
        this.state = state;
        this.screens = screens;
    }

    @Subscribe
    public void onChat(@NotNull PlayerChatEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());

        Optional<PunishmentSyncMessage> mute;
        try {
            // Served from the local mirror once the player's state is known (zero network calls,
            // since this event fires on every message); unknown state is loaded once and cached.
            mute = this.state.activeMute(uuidHash);
        } catch (PunishmentState.Unavailable e) {
            LOGGER.error("Could not check the mute state of {}, delivering without scoring: {}", player.getUsername(), e.getMessage());
            return;
        }
        if (mute.isPresent()) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            player.sendMessage(this.screens.screen(
                    mute.get(), PunishmentScreens.localeOf(player), PunishmentScreens.Surface.CHAT, false));
            return;
        }
        submitChatSignal(player, uuidHash, event.getMessage());
    }

    /**
     * Scores the message for the chat section's ELO. Best-effort - a failure here must never
     * affect chat delivery, which has already been decided by the mute check above.
     */
    private void submitChatSignal(@NotNull Player player, @NotNull String uuidHash, @NotNull String message) {
        try {
            this.eloApi.submitChatSignal(new EloChatSignalRequest().owner(uuidHash).message(message));
        } catch (ApiException e) {
            LOGGER.error("Failed to submit chat signal for ELO scoring for player {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
