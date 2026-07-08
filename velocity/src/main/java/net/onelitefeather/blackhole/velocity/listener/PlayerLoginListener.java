package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.onelitefeather.blackhole.client.api.EvasionApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EvasionRecordDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.client.model.SessionInfoDTO;
import net.onelitefeather.blackhole.velocity.component.PunishmentTemplateComponent;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
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
    private final EvasionApi evasionApi;
    private final BlackholeConfig config;
    private final RedisSyncService redisSyncService;

    @Inject
    public PlayerLoginListener(@NotNull ApiClient apiClient, @NotNull BlackholeConfig config, @NotNull RedisSyncService redisSyncService) {
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.evasionApi = new EvasionApi(apiClient);
        this.config = config;
        this.redisSyncService = redisSyncService;
    }

    /**
     * Handles the logic to disconnect a player if they have an active ban
     * @param event the event
     */
    @Subscribe
    public void onLogin(@NotNull LoginEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());

        // Recorded regardless of ban outcome - catching a banned player re-joining on a fresh
        // account from the same IP is the whole point of ban-evasion detection.
        recordEvasionSignal(player, uuidHash);

        try {
            var ban = this.redisSyncService.fetchAndTrack(this.config.getTenantId(), uuidHash);
            if (ban.isPresent() && PunishType.NETWORK.toString().equals(ban.get().type())) {
                kickForActiveNetworkBan(player, uuidHash);
                return;
            }
            // Redis gave a definitive answer (no active NETWORK ban) - every other proxy already
            // saw the same state, so there's no need to also ask the backend over HTTP.
            recordSessionInfo(player, uuidHash);
            return;
        } catch (RuntimeException e) {
            LOGGER.debug("Redis unavailable at login for {}, falling back to HTTP ban check: {}", player.getUsername(), e.getMessage());
        }

        Optional<PunishProfileDTO> profileOptional;
        try {
            profileOptional = Optional.of(this.punishProfileApi.getById(this.config.getTenantId(), uuidHash));
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for player {}: {}", player.getUsername(), e.getMessage());
            return;
        }

        PunishProfileDTO punishProfile = profileOptional.get();
        var activeBanDTO = punishProfile.getActiveBan();
        if (activeBanDTO != null && activeBanDTO.getTemplate().getType() == PunishType.NETWORK) {
            event.getPlayer().disconnect(PunishmentTemplateComponent.of(activeBanDTO.getTemplate(), punishProfile));
            return;
        }

        recordSessionInfo(player, uuidHash);
    }

    /**
     * Redis only carries enough of a snapshot to know a NETWORK ban is active, not the full
     * template needed to render the kick message - one HTTP call still builds that, same as the
     * pre-Redis behavior.
     */
    private void kickForActiveNetworkBan(Player player, String uuidHash) {
        try {
            PunishProfileDTO punishProfile = this.punishProfileApi.getById(this.config.getTenantId(), uuidHash);
            var activeBanDTO = punishProfile.getActiveBan();
            if (activeBanDTO != null) {
                player.disconnect(PunishmentTemplateComponent.of(activeBanDTO.getTemplate(), punishProfile));
            }
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for banned player {}: {}", player.getUsername(), e.getMessage());
        }
    }

    private void recordEvasionSignal(@NotNull Player player, @NotNull String uuidHash) {
        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        try {
            this.evasionApi.recordEvasionSighting(this.config.getTenantId(), new EvasionRecordDTO().owner(uuidHash).ip(ip));
        } catch (ApiException e) {
            LOGGER.debug("Evasion signal not recorded for {}: {}", player.getUsername(), e.getMessage());
        }
    }

    private void recordSessionInfo(@NotNull Player player, @NotNull String uuidHash) {
        try {
            this.punishProfileApi.updateSessionInfo(this.config.getTenantId(), uuidHash,
                    new SessionInfoDTO().tenantId(this.config.getTenantId()).owner(uuidHash).protocolVersion(player.getProtocolVersion().getProtocol()));
        } catch (ApiException e) {
            LOGGER.debug("Session info not recorded for {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
