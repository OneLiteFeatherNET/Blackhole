package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.api.EvasionApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EvasionRecordDTO;
import net.onelitefeather.blackhole.client.model.SessionInfoDTO;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Denies the login of players with an active punishment in a BAN section. The player's punishments
 * come from the Redis mirror or, if Redis is down, from the backend profile; if neither answers
 * the login is allowed.
 *
 * @since 1.0.0
 * @version 1.1.0
 * @author theEvilReaper
 */
public final class PlayerLoginListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerLoginListener.class);
    private final PunishProfileApi punishProfileApi;
    private final EvasionApi evasionApi;
    private final PunishmentState state;
    private final PunishmentScreens screens;

    @Inject
    public PlayerLoginListener(@NotNull ApiClient apiClient, @NotNull PunishmentState state, @NotNull PunishmentScreens screens) {
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.evasionApi = new EvasionApi(apiClient);
        this.state = state;
        this.screens = screens;
    }

    /**
     * Disconnects the player if they have an active punishment in a BAN section.
     *
     * @param event the event
     */
    @Subscribe
    public void onLogin(@NotNull LoginEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());

        // Recorded regardless of ban outcome - catching a banned player re-joining on a fresh
        // account from the same IP is the whole point of ban-evasion detection.
        recordEvasionSignal(player, uuidHash);

        Optional<PunishmentSyncMessage> ban;
        try {
            ban = this.state.onLogin(uuidHash);
        } catch (PunishmentState.Unavailable e) {
            LOGGER.error("Could not check the punishments of {}, allowing the login: {}", player.getUsername(), e.getMessage());
            return;
        }
        if (ban.isPresent()) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    this.screens.screen(ban.get(), PunishmentScreens.localeOf(player), PunishmentScreens.Surface.DISCONNECT, false)));
            return;
        }
        recordSessionInfo(player, uuidHash);
    }

    private void recordEvasionSignal(@NotNull Player player, @NotNull String uuidHash) {
        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        try {
            this.evasionApi.recordEvasionSighting(new EvasionRecordDTO().owner(uuidHash).ip(ip));
        } catch (ApiException e) {
            LOGGER.debug("Evasion signal not recorded for {}: {}", player.getUsername(), e.getMessage());
        }
    }

    private void recordSessionInfo(@NotNull Player player, @NotNull String uuidHash) {
        try {
            this.punishProfileApi.updateSessionInfo(uuidHash,
                    new SessionInfoDTO().owner(uuidHash).protocolVersion(player.getProtocolVersion().getProtocol()));
        } catch (ApiException e) {
            LOGGER.debug("Session info not recorded for {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
