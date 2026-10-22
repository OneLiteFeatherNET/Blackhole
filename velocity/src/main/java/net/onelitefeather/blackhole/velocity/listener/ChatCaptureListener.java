package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

/**
 * Feeds the delivered public chat into the {@link ChatBuffer}. Runs after the other chat listeners, so
 * a message that was denied (a muted player's) is never buffered, and does nothing while capture is
 * off. Commands are never buffered. The work per message is one hash and one bounded deque insert.
 */
public final class ChatCaptureListener {

    private final ChatBuffer buffer;

    @Inject
    public ChatCaptureListener(@NotNull ChatBuffer buffer) {
        this.buffer = buffer;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onChat(@NotNull PlayerChatEvent event) {
        if (!this.buffer.enabled() || !event.getResult().isAllowed()) {
            return;
        }
        String message = event.getResult().getMessage().orElse(event.getMessage());
        // Velocity routes commands elsewhere on current clients; this guards legacy clients.
        if (message.isBlank() || message.startsWith("/")) {
            return;
        }
        Player player = event.getPlayer();
        String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
        if (server == null) {
            return;
        }
        this.buffer.record(UUIDConverter.convertToSHA(player.getUniqueId()), server, message);
    }
}
