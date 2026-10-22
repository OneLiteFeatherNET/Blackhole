package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

/**
 * Drops a player's mirrored punishment state and buffered chat when they leave, so both hold online
 * players only.
 */
public final class PlayerDisconnectListener {

    private final PunishmentMirror mirror;
    private final ChatBuffer chatBuffer;

    @Inject
    public PlayerDisconnectListener(@NotNull PunishmentMirror mirror, @NotNull ChatBuffer chatBuffer) {
        this.mirror = mirror;
        this.chatBuffer = chatBuffer;
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        String owner = UUIDConverter.convertToSHA(event.getPlayer().getUniqueId());
        this.mirror.forget(owner);
        this.chatBuffer.clear(owner);
    }
}
