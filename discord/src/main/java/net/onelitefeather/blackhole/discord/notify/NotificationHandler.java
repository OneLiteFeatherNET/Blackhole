package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationKind;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationResult;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageRef;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Posts a team message for a new report or appeal and edits it when the item is resolved. Stateless
 * across restarts: the message of an item is found again by the marker in its footer, so a redelivered
 * event, an event that arrives after its resolution and a restart never produce a second message. A
 * small in-memory set of handled event ids additionally short-circuits plain redeliveries without any
 * backend or Discord call.
 */
public final class NotificationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationHandler.class);
    private static final int HANDLED_LIMIT = 2048;

    /** What the consumer should do with the message afterwards. */
    public enum Outcome {
        /** Handled (or nothing to do): acknowledge. */
        DONE,
        /** A dependency was unavailable: put the message back and try again later. */
        RETRY,
        /** It can never succeed (malformed, unknown item, no access): acknowledge and move on. */
        DROP
    }

    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final EmbedRenderer renderer;
    private final long channelId;
    private final Set<String> handled = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > HANDLED_LIMIT;
        }
    });

    public NotificationHandler(
            @NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull EmbedRenderer renderer, long teamChannelId
    ) {
        this.backend = backend;
        this.discord = discord;
        this.renderer = renderer;
        this.channelId = teamChannelId;
    }

    public synchronized @NotNull Outcome handle(@NotNull NotificationEvent event) {
        if (this.handled.contains(event.eventId())) {
            return Outcome.DONE;
        }
        NotificationResult looked = event.kind() == NotificationKind.REPORT
                ? this.backend.report(event.identifier())
                : this.backend.appeal(event.identifier());
        Notification notification;
        switch (looked) {
            case NotificationResult.Found found -> notification = found.notification();
            case NotificationResult.NotFound ignored -> {
                LOGGER.warn("Event {} ({}) names an item the backend does not know", event.eventId(), event.eventType());
                return Outcome.DROP;
            }
            case NotificationResult.Unavailable ignored -> {
                return Outcome.RETRY;
            }
        }
        try {
            Optional<MessageRef> existing = this.discord.find(this.channelId, EmbedRenderer.marker(notification.kind(), notification.identifier()));
            if (existing.isEmpty() && "INELIGIBLE".equals(notification.status())) {
                return remember(event); // an appeal that cannot be reviewed needs no team message
            }
            MessageSpec spec = this.renderer.render(notification);
            if (existing.isPresent()) {
                // Editing never pings again; a resolved item drops the role mention text (its content is empty).
                this.discord.edit(existing.get(), spec);
            } else {
                this.discord.post(this.channelId, spec);
            }
            return remember(event);
        } catch (GatewayException e) {
            if (e.permanent()) {
                LOGGER.error("Event {} cannot be delivered: {}", event.eventId(), e.getMessage());
                return Outcome.DROP;
            }
            LOGGER.warn("Event {} will be retried: {}", event.eventId(), e.getMessage());
            return Outcome.RETRY;
        }
    }

    private Outcome remember(NotificationEvent event) {
        this.handled.add(event.eventId());
        return Outcome.DONE;
    }
}
