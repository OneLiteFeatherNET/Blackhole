package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ChatSignalDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
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
    private final RedisSyncService redisSyncService;

    @Inject
    public PlayerChatListener(@NotNull ApiClient blackholeClient, @NotNull BlackholeConfig config, @NotNull RedisSyncService redisSyncService) {
        this.punishProfileApi = new PunishProfileApi(blackholeClient);
        this.eloApi = new EloApi(blackholeClient);
        this.config = config;
        this.redisSyncService = redisSyncService;
    }

    @Subscribe
    public void onChat(@NotNull PlayerChatEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());

        Optional<PunishmentSyncMessage> cached = this.redisSyncService.getChatBanFast(uuidHash);
        if (cached != null) {
            // A definitive answer from a prior lookup this session - zero network calls, the
            // whole point of this cache, since PlayerChatEvent fires on every single message.
            if (cached.isPresent()) {
                denyChat(event, player);
            } else {
                submitChatSignal(player, uuidHash, event.getMessage());
            }
            return;
        }

        // Cache miss (e.g. Redis was down at this player's login) - fall back to the pre-Redis
        // HTTP check once, then seed the cache so every subsequent message this session is a hit.
        PunishProfileDTO punishProfile;
        try {
            punishProfile = this.punishProfileApi.getById(this.config.getTenantId(), uuidHash);
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for player {}: {}", player.getUsername(), e.getMessage());
            return;
        }

        var activeChatBan = punishProfile.getActiveChatBan();
        if (activeChatBan != null) {
            this.redisSyncService.seedChatBan(uuidHash, Optional.of(new PunishmentSyncMessage(
                    this.config.getTenantId(), uuidHash, PunishmentSyncMessage.SLOT_CHAT_BAN, PunishmentSyncMessage.STATE_SET,
                    "CHAT", activeChatBan.getIdentifier(), null, null
            )));
            denyChat(event, player);
            return;
        }
        this.redisSyncService.seedChatBan(uuidHash, Optional.empty());
        submitChatSignal(player, uuidHash, event.getMessage());
    }

    private void denyChat(PlayerChatEvent event, Player player) {
        event.setResult(PlayerChatEvent.ChatResult.denied());
        player.sendMessage(Component.translatable("punishment.error.muted"));
    }

    /**
     * Scores the message for the dual-ELO chat track. Best-effort - a failure here must never
     * affect chat delivery, which has already been decided by the mute check above.
     */
    private void submitChatSignal(@NotNull Player player, @NotNull String uuidHash, @NotNull String message) {
        try {
            this.eloApi.submitChatSignal(this.config.getTenantId(), new ChatSignalDTO().owner(uuidHash).message(message));
        } catch (ApiException e) {
            LOGGER.error("Failed to submit chat signal for ELO scoring for player {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
