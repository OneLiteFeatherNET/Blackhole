package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

/**
 * Drops a player's mirrored punishment state, buffered chat and mute notice memory when they leave, so all hold online
 * players only.
 */
public final class PlayerDisconnectListener {

    private final PunishmentMirror mirror;
    private final ChatBuffer chatBuffer;
    private final MuteNotices notices;

    @Inject
    public PlayerDisconnectListener(@NotNull PunishmentMirror mirror, @NotNull ChatBuffer chatBuffer, @NotNull MuteNotices notices) {
        this.mirror = mirror;
        this.chatBuffer = chatBuffer;
        this.notices = notices;
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        String owner = UUIDConverter.convertToSHA(event.getPlayer().getUniqueId());
        this.mirror.forget(owner);
        this.chatBuffer.clear(owner);
        this.notices.forget(owner);
    }
}
