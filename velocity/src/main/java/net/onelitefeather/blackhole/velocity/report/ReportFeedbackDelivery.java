package net.onelitefeather.blackhole.velocity.report;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ReportFeedbackResponse;
import net.onelitefeather.blackhole.velocity.component.ReportMessages;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Tells reporters what became of their reports: fetch the messages waiting for the player, show each, and
 * acknowledge it so it is not shown again. The same routine serves the player's login (offline reporters
 * hear at their next login) and the poke the backend sends when a report is resolved (online reporters hear
 * at once); a poke reaches every proxy, but only the proxy the reporter is connected to finds them.
 *
 * <p>The messages say whether action was taken, never which punishment. A message is acknowledged only
 * after it was shown, so a failed acknowledgement can repeat a message but never lose one; a message this
 * proxy has shown recently is not shown twice if login and poke race.</p>
 */
public final class ReportFeedbackDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportFeedbackDelivery.class);
    private static final int REMEMBERED = 256;

    private final BackendGateway backend;
    private final Supplier<ZoneId> zone;
    private final Executor async;
    private final Set<UUID> shown = new LinkedHashSet<>();

    /**
     * @param async runs the backend calls off the event thread
     */
    public ReportFeedbackDelivery(@NotNull BackendGateway backend, @NotNull Supplier<ZoneId> zone, @NotNull Executor async) {
        this.backend = backend;
        this.zone = zone;
        this.async = async;
    }

    /** Delivers the player's waiting messages on the async executor. */
    public void deliverLater(@NotNull Player player) {
        this.async.execute(() -> deliver(player));
    }

    /** Delivers the waiting messages to the player of this proxy whose owner hash it is, if there is one. */
    public void poked(@NotNull String ownerHash, @NotNull Collection<? extends Player> online) {
        for (Player player : online) {
            if (UUIDConverter.convertToSHA(player.getUniqueId()).equals(ownerHash)) {
                deliverLater(player);
            }
        }
    }

    /** Fetches, shows and acknowledges the player's waiting messages; a backend failure only means they hear later. */
    public void deliver(@NotNull Player player) {
        List<ReportFeedbackResponse> waiting;
        try {
            waiting = this.backend.reportFeedback(UUIDConverter.convertToSHA(player.getUniqueId()));
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Report feedback could not be fetched, the reporter hears at the next login ({})", e.getClass().getSimpleName());
            return;
        }
        for (ReportFeedbackResponse message : waiting) {
            if (!remember(message.getIdentifier())) {
                continue;
            }
            player.sendMessage(ReportMessages.feedback(message, this.zone.get()));
            try {
                this.backend.acknowledgeReportFeedback(message.getIdentifier());
            } catch (ApiException | RuntimeException e) {
                LOGGER.warn("Report feedback could not be acknowledged ({})", e.getClass().getSimpleName());
            }
        }
    }

    /** @return whether the message was not shown by this proxy recently */
    private boolean remember(UUID identifier) {
        synchronized (this.shown) {
            if (!this.shown.add(identifier)) {
                return false;
            }
            if (this.shown.size() > REMEMBERED) {
                Iterator<UUID> oldest = this.shown.iterator();
                oldest.next();
                oldest.remove();
            }
            return true;
        }
    }
}
