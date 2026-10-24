package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which channel belongs to which ticket, as last read from the backend. It is a cache of the backend, never the truth:
 * the poll replaces it, and a channel missing here only means "ask the backend again".
 */
public final class TicketRegistry {

    private final Map<Long, Ticket> byChannel = new ConcurrentHashMap<>();

    public void replaceAll(@NotNull Collection<Ticket> tickets) {
        Map<Long, Ticket> fresh = new ConcurrentHashMap<>();
        for (Ticket ticket : tickets) {
            if (ticket.channel() != null && ticket.state() != State.DELETED) {
                fresh.put(ticket.channel(), ticket);
            }
        }
        this.byChannel.keySet().retainAll(fresh.keySet());
        this.byChannel.putAll(fresh);
    }

    public void put(@NotNull Ticket ticket) {
        Long channel = ticket.channel();
        if (channel != null && ticket.state() != State.DELETED) {
            this.byChannel.put(channel, ticket);
        }
    }

    public void remove(long channelId) {
        this.byChannel.remove(channelId);
    }

    public @Nullable Ticket byChannel(long channelId) {
        return this.byChannel.get(channelId);
    }

    public @NotNull List<Ticket> all() {
        return List.copyOf(this.byChannel.values());
    }
}
