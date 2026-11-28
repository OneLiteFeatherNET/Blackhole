package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.CaseRead;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationResult;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageRef;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.Decision;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.Phase;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.View;
import net.onelitefeather.blackhole.discord.ticket.TicketRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the staff control message of each ticket channel in step with the backend. It posts the message when a channel is ready,
 * and redraws it on every case event of its case and after a staff press. The message is a view: every redraw reads the ticket,
 * the appeal and the case again. A Discord failure is logged and counted, and the next event or press draws it again; nothing is
 * retried in a loop.
 */
public final class ControlMessageSync {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlMessageSync.class);
    /** The appeal states that are still undecided. */
    private static final Set<String> PENDING = Set.of("SUBMITTED", "ELIGIBLE_PENDING_REVIEW", "IN_REVIEW");

    private final String panelUrl;
    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final TicketRegistry registry;
    private final Metrics metrics;
    private final Clock clock;
    /** The message of each ticket, as found or posted; a cache of the Discord lookup, dropped on a failure. */
    private final Map<String, MessageRef> messages = new ConcurrentHashMap<>();

    public ControlMessageSync(@NotNull String panelUrl, @NotNull BackendGateway backend, @NotNull DiscordGateway discord,
                              @NotNull TicketRegistry registry, @NotNull Metrics metrics, @NotNull Clock clock) {
        this.panelUrl = panelUrl;
        this.backend = backend;
        this.discord = discord;
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Draws the control message of a ticket's channel; a channel that is gone or a backend that does not answer changes nothing. */
    public void redraw(@NotNull Ticket ticket) {
        Long channel = ticket.channel();
        if (channel == null) {
            return;
        }
        Optional<MessageSpec> spec = spec(ticket);
        if (spec.isEmpty()) {
            return;
        }
        try {
            MessageRef message = this.messages.get(ticket.id());
            if (message == null) {
                message = this.discord.find(channel, StaffControlRenderer.marker(ticket.id())).orElse(null);
            }
            if (message == null) {
                this.messages.put(ticket.id(), this.discord.post(channel, spec.get()));
            } else {
                this.discord.edit(message, spec.get());
                this.messages.put(ticket.id(), message);
            }
        } catch (GatewayException e) {
            this.messages.remove(ticket.id());
            this.metrics.increment(Counter.DISCORD_ERRORS);
            LOGGER.warn("Discord refused the staff control message (permanent={})", e.permanent());
        }
    }

    /**
     * A case event arrived: redraws the control message of every open ticket whose case it names. The event names the case by its
     * punishment identifier, the same reference the case page uses.
     *
     * @param punishmentIdentifier the case of the event, null when the event names none
     */
    public void onCaseEvent(@Nullable String punishmentIdentifier) {
        if (punishmentIdentifier == null) {
            return;
        }
        for (Ticket ticket : this.registry.all()) {
            if (ticket.channel() == null || ticket.state() == State.DELETED) {
                continue;
            }
            if (punishmentIdentifier.equals(caseReference(ticket).orElse(null))) {
                redraw(ticket);
            }
        }
    }

    private Optional<String> caseReference(Ticket ticket) {
        if (this.backend.appeal(ticket.appealId()) instanceof NotificationResult.Found found) {
            return Optional.ofNullable(found.notification().punishmentIdentifier());
        }
        return Optional.empty();
    }

    /** Reads what the message shows; empty when the backend cannot say (the message is then left as it was). */
    private Optional<MessageSpec> spec(Ticket ticket) {
        if (!(this.backend.appeal(ticket.appealId()) instanceof NotificationResult.Found found)) {
            return Optional.empty();
        }
        Notification appeal = found.notification();
        String caseReference = appeal.punishmentIdentifier();
        if (caseReference == null || !(this.backend.caseSnapshot(caseReference) instanceof CaseRead.Found snapshot)) {
            return Optional.empty();
        }
        View view = new View(ticket.id(), caseReference, this.panelUrl, phase(ticket.state()), ticket.frozen(), decision(appeal.status()),
                snapshot.snapshot().assigneeName());
        return Optional.of(StaffControlRenderer.render(view, Instant.now(this.clock)));
    }

    static Phase phase(State state) {
        return switch (state) {
            case CLOSING -> Phase.CLOSING;
            case CLOSED, ARCHIVED, DELETED -> Phase.CLOSED;
            default -> Phase.OPEN;
        };
    }

    static Decision decision(String status) {
        if (PENDING.contains(status)) {
            return Decision.NONE;
        }
        return "DENIED".equals(status) ? Decision.REJECTED : Decision.ACCEPTED;
    }
}
