package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Attachment;
import net.onelitefeather.blackhole.discord.backend.Tickets.Embed;
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

    public CatchUp(@NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull Metrics metrics) {
        this.backend = backend;
        this.discord = discord;
        this.metrics = metrics;
    }

    /** Maps a message to the backend's item. The text is never logged. */
    public static @NotNull IngestItem toItem(@NotNull IncomingMessage message) {
        List<Attachment> attachments = message.attachments().stream()
                .map(a -> new Attachment(a.kind(), a.id(), a.name(), a.contentType(), a.size(), a.width(), a.height())).toList();
        List<Embed> embeds = message.embeds().stream().map(e -> new Embed(e.type(), e.host())).toList();
        return new IngestItem(Long.toUnsignedString(message.messageId()), Long.toUnsignedString(message.authorId()), message.authorName(),
                message.bot(), message.hasStaffRole(), message.createdAt().toEpochMilli(),
                message.editedAt() == null ? null : message.editedAt().toEpochMilli(), message.text(), attachments, embeds);
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
                List<IngestItem> items = page.stream().filter(m -> !m.self()).map(CatchUp::toItem).toList();
                if (!items.isEmpty() && !send(ticket, IngestBatch.of(items))) {
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
                if (!others.isEmpty() && !send(ticket, IngestBatch.of(others.stream().map(CatchUp::toItem).toList()))) {
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
