package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Posts one team message per join guard or area ban event. The event needs no backend read: its message is
 * rendered from the payload. A message already in the channel (found by its marker) is not posted again, so
 * a redelivery or a restart never doubles it. A small in-memory set of handled ids short-circuits plain
 * redeliveries without any Discord call.
 */
public final class GuardNotificationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardNotificationHandler.class);
    private static final int HANDLED_LIMIT = 2048;

    private final DiscordGateway discord;
    private final GuardMessageRenderer renderer;
    private final long channelId;
    private final Set<String> handled = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > HANDLED_LIMIT;
        }
    });

    public GuardNotificationHandler(@NotNull DiscordGateway discord, @NotNull GuardMessageRenderer renderer, long teamChannelId) {
        this.discord = discord;
        this.renderer = renderer;
        this.channelId = teamChannelId;
    }

    public synchronized @NotNull NotificationHandler.Outcome handle(@NotNull GuardEvent event) {
        if (this.handled.contains(event.eventId())) {
            return NotificationHandler.Outcome.DONE;
        }
        try {
            if (this.discord.find(this.channelId, GuardMessageRenderer.marker(event.eventId())).isEmpty()) {
                this.discord.post(this.channelId, this.renderer.render(event));
            }
            this.handled.add(event.eventId());
            return NotificationHandler.Outcome.DONE;
        } catch (GatewayException e) {
            if (e.permanent()) {
                LOGGER.error("Guard event {} cannot be delivered: {}", event.eventId(), e.getMessage());
                return NotificationHandler.Outcome.DROP;
            }
            LOGGER.warn("Guard event {} will be retried: {}", event.eventId(), e.getMessage());
            return NotificationHandler.Outcome.RETRY;
        }
    }
}
