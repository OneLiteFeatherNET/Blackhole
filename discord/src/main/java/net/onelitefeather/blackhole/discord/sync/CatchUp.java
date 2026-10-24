package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.attachment.InboundFiles;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestBatch;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingMessage;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Discord to backend, for what the gateway did not deliver live: reads a channel's history and posts it. Two uses:
 * after a start, an outage or a failed call it reads everything after the backend's cursor ({@link #ingestSince}); when a
 * ticket closes it reads the whole channel and sends the list of messages present, so the backend can record edits and
 * deletes it missed ({@link #rescan}). Everything is idempotent by message id. Not thread safe: the caller holds the ticket lock.
 */
public final class CatchUp {

    private static final Logger LOGGER = LoggerFactory.getLogger(CatchUp.class);
    static final int PAGE = 100;

    /**
     * What the channel holds, to be proven to the backend.
     *
     * @param count         how many messages written by someone other than the bot
     * @param lastMessageId the highest id among them, null when there are none
     */
    public record Proof(int count, @Nullable String lastMessageId) {
    }

    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final Metrics metrics;

    private final InboundFiles inbound;

    public CatchUp(@NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull Metrics metrics, @NotNull InboundFiles inbound) {
        this.backend = backend;
        this.discord = discord;
        this.metrics = metrics;
        this.inbound = inbound;
    }

    /**
     * Maps a message to the backend's item (Markdown mapped, files uploaded for the player and verified staff). The text is never logged.
     *
     * @return empty when the backend could not take a file of the message right now: hold it and try again later
     */
    public @NotNull Optional<IngestItem> item(@NotNull Ticket ticket, @NotNull IncomingMessage message) {
        return this.inbound.toItem(ticket, message);
    }

    /** @return false when the backend or Discord could not be reached, so the caller tries again later */
    public boolean ingestSince(@NotNull Ticket ticket) {
        Long channel = ticket.channel();
        if (channel == null) {
            return true;
        }
        this.metrics.increment(Counter.CATCH_UPS);
        long after = parse(ticket.ingestedCursor());
        try {
            while (true) {
                List<IncomingMessage> page = this.discord.history(channel, after, PAGE);
                if (page.isEmpty()) {
                    return true;
                }
                List<IngestItem> items = new ArrayList<>();
                boolean held = false;
                for (IncomingMessage message : page) {
                    if (message.self()) {
                        continue;
                    }
                    Optional<IngestItem> item = item(ticket, message);
                    if (item.isEmpty()) {
                        held = true;
                        break;
                    }
                    items.add(item.get());
                }
                // A held message stops the pass: what came before it is recorded, nothing after it, so the cursor never skips it.
                if (!items.isEmpty() && !send(ticket, IngestBatch.of(items))) {
                    return false;
                }
                if (held) {
                    return false;
                }
                after = page.getLast().messageId();
                if (page.size() < PAGE) {
                    return true;
                }
            }
        } catch (GatewayException e) {
            this.metrics.increment(Counter.DISCORD_ERRORS);
            LOGGER.warn("Reading a ticket channel's history failed (permanent={})", e.permanent());
            return false;
        }
    }

    /**
     * Reads the entire channel from its first message, posts it and finishes with the list of ids present
     * ({@code complete}). The backend inserts what is missing, records edits made while the bot was away and marks
     * messages that are gone as deleted.
     *
     * @return the proof to close with, or empty when an outage stopped the scan
     */
    public @NotNull Optional<Proof> rescan(@NotNull Ticket ticket) {
        Long channel = ticket.channel();
        if (channel == null) {
            return Optional.empty();
        }
        this.metrics.increment(Counter.CATCH_UPS);
        List<String> present = new ArrayList<>();
        String last = null;
        long after = 0;
        try {
            while (true) {
                List<IncomingMessage> page = this.discord.history(channel, after, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                List<IncomingMessage> others = page.stream().filter(m -> !m.self()).toList();
                List<IngestItem> items = new ArrayList<>();
                for (IncomingMessage message : others) {
                    Optional<IngestItem> item = item(ticket, message);
                    if (item.isEmpty()) {
                        return Optional.empty();
                    }
                    items.add(item.get());
                }
                if (!items.isEmpty() && !send(ticket, IngestBatch.of(items))) {
                    return Optional.empty();
                }
                for (IncomingMessage message : others) {
                    present.add(Long.toUnsignedString(message.messageId()));
                    last = Long.toUnsignedString(message.messageId());
                }
                after = page.getLast().messageId();
                if (page.size() < PAGE) {
                    break;
                }
            }
        } catch (GatewayException e) {
            this.metrics.increment(Counter.DISCORD_ERRORS);
            LOGGER.warn("Rescanning a ticket channel failed (permanent={})", e.permanent());
            return Optional.empty();
        }
        if (!send(ticket, new IngestBatch(List.of(), List.of(), true, present))) {
            return Optional.empty();
        }
        return Optional.of(new Proof(present.size(), last));
    }

    private boolean send(Ticket ticket, IngestBatch batch) {
        Result<?> result = this.backend.ingest(ticket.id(), batch);
        if (result instanceof Result.Failed<?> failed) {
            this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
            LOGGER.warn("Recording messages failed with status {}", failed.status());
            return false;
        }
        this.metrics.increment(Counter.INGESTS);
        return true;
    }

    private static long parse(String cursor) {
        try {
            return Long.parseUnsignedLong(cursor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
