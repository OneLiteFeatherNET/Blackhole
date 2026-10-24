package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Deletes archived ticket channels after the configured delay (default 30 days). The age is taken from the channel's last
 * message, which is at least the bot's archive notice, because the backend does not report when a ticket was archived. A delay
 * of zero keeps archived channels for ever. The gate before every delete is {@link TicketLifecycle#deleteIfDue}.
 */
public final class ArchiveSweeper {

    private final BackendGateway backend;
    private final TicketLifecycle lifecycle;
    private final Duration deleteAfter;
    private final Metrics metrics;

    public ArchiveSweeper(@NotNull BackendGateway backend, @NotNull TicketLifecycle lifecycle, @NotNull Duration deleteAfter, @NotNull Metrics metrics) {
        this.backend = backend;
        this.lifecycle = lifecycle;
        this.deleteAfter = deleteAfter;
        this.metrics = metrics;
    }

    /** @return how many channels were deleted */
    public int sweep() {
        if (this.deleteAfter.isZero()) {
            return 0;
        }
        Result<List<Ticket>> archived = this.backend.tickets(Set.of(State.ARCHIVED));
        if (archived instanceof Result.Failed<List<Ticket>>) {
            this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
            return 0;
        }
        int deleted = 0;
        for (Ticket ticket : ((Result.Ok<List<Ticket>>) archived).value()) {
            if (this.lifecycle.deleteIfDue(ticket)) {
                deleted++;
            }
        }
        return deleted;
    }
}
