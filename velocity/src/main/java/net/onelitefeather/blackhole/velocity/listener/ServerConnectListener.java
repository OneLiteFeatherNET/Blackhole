package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * Keeps players with an active punishment in a SERVER section off the backend servers that section
 * covers: the connect is denied and the player stays where they are. A player without a current
 * server (the very first connect after login) is disconnected instead, as there is nowhere to stay.
 */
public final class ServerConnectListener {

    private final PunishmentState state;
    private final PunishmentScreens screens;

    @Inject
    public ServerConnectListener(@NotNull PunishmentState state, @NotNull PunishmentScreens screens) {
        this.state = state;
        this.screens = screens;
    }

    @Subscribe
    public void onServerPreConnect(@NotNull ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getOriginalServer().getServerInfo().getName();
        Optional<PunishmentSyncMessage> ban = this.state.activeServerBan(UUIDConverter.convertToSHA(player.getUniqueId()), serverName);
        if (ban.isEmpty()) {
            return;
        }
        Locale locale = PunishmentScreens.localeOf(player);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        if (player.getCurrentServer().isPresent()) {
            player.sendMessage(this.screens.screen(ban.get(), locale, PunishmentScreens.Surface.CHAT, false));
        } else {
            player.disconnect(this.screens.screen(ban.get(), locale, PunishmentScreens.Surface.DISCONNECT, false));
        }
    }
}
