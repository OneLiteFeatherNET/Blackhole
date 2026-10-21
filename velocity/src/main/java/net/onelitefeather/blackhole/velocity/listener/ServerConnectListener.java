package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.component.PunishmentMessages;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Keeps players with an active punishment in a SERVER section off the backend servers that section
 * covers: the connect is denied and the player stays where they are. A player without a current
 * server (the very first connect after login) is disconnected instead, as there is nowhere to stay.
 */
public final class ServerConnectListener {

    private final PunishmentState state;

    @Inject
    public ServerConnectListener(@NotNull PunishmentState state) {
        this.state = state;
    }

    @Subscribe
    public void onServerPreConnect(@NotNull ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getOriginalServer().getServerInfo().getName();
        Optional<PunishmentSyncMessage> ban = this.state.activeServerBan(UUIDConverter.convertToSHA(player.getUniqueId()), serverName);
        if (ban.isEmpty()) {
            return;
        }
        Component message = PunishmentMessages.forPlayer(ban.get());
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        if (player.getCurrentServer().isPresent()) {
            player.sendMessage(message);
        } else {
            player.disconnect(message);
        }
    }
}
