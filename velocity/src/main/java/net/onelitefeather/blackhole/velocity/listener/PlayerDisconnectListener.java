package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

/**
 * Drops a player's mirrored punishment state when they leave, so the mirror holds online players only.
 */
public final class PlayerDisconnectListener {

    private final PunishmentMirror mirror;

    @Inject
    public PlayerDisconnectListener(@NotNull PunishmentMirror mirror) {
        this.mirror = mirror;
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        this.mirror.forget(UUIDConverter.convertToSHA(event.getPlayer().getUniqueId()));
    }
}
