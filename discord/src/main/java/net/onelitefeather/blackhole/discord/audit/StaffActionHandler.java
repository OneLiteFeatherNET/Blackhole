package net.onelitefeather.blackhole.discord.audit;

import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * What the staff audit consumer does with one message: parse it, render it, post it. Delivered and finally refused entries
 * are acknowledged; an entry that could not be posted for now goes back to the stream. Never throws.
 */
public final class StaffActionHandler {

    /** The durable consumer on the events stream. */
    public static final String DURABLE = "discord-audit";
    /** The event types the consumer receives. */
    public static final String[] EVENT_TYPES = {StaffActionEvent.EVENT_TYPE};
    /** One message at a time, so the channel shows the entries in the order they were made. */
    public static final int IN_FLIGHT = 1;
    /** The retries of one entry happen in-process and can take a while; a longer wait avoids a redelivery in the middle. */
    public static final Duration ACK_WAIT = Duration.ofMinutes(10);

    private static final Logger LOGGER = LoggerFactory.getLogger(StaffActionHandler.class);

    private final AuditWebhookPoster poster;
    private final AuditEmbedRenderer renderer;
    private final Metrics metrics;

    public StaffActionHandler(@NotNull AuditWebhookPoster poster, @NotNull AuditEmbedRenderer renderer, @NotNull Metrics metrics) {
        this.poster = poster;
        this.renderer = renderer;
        this.metrics = metrics;
    }

    /**
     * Called once for a message whose last allowed delivery failed: it is acknowledged and gone from the channel. Logs the
     * audit id only, never the webhook.
     */
    public void onExhausted(byte[] body) {
        this.metrics.increment(Metrics.Counter.AUDIT_DROPPED);
        String auditId = StaffActionEvent.parse(body).map(StaffActionEvent::auditId).orElse("unknown");
        LOGGER.warn("Audit entry {} was not posted after its last delivery and is dropped from the channel", auditId);
    }

    public @NotNull Outcome onMessage(byte[] body) {
        Optional<StaffActionEvent> event = StaffActionEvent.parse(body);
        if (event.isEmpty()) {
            LOGGER.warn("Dropping a message that is not a staff action event");
            return Outcome.DROP;
        }
        return switch (this.poster.post(event.get().auditId(), this.renderer.render(event.get()))) {
            case DELIVERED -> Outcome.DONE;
            case REJECTED -> Outcome.DROP;
            case UNDELIVERED -> Outcome.RETRY;
        };
    }
}
