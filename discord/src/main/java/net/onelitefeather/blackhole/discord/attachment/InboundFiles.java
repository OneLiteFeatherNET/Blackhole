package net.onelitefeather.blackhole.discord.attachment;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Attachment;
import net.onelitefeather.blackhole.discord.backend.Tickets.Embed;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.backend.Tickets.Uploaded;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingAttachment;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingMessage;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageRef;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.text.DiscordMarkdown;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a Discord message into the backend's ingest item. The text goes through {@link DiscordMarkdown}. For a message of the
 * bound player or of someone holding the staff role, each file is downloaded under the {@link AttachmentFetcher} policy and
 * uploaded to the backend, which decides (type, size, scan, and whether the author is the player or verified staff) and answers
 * with an attachment id or a reject code; everything else stays metadata. A rejected file is recorded with its code and the
 * message gets a warning reaction. An outage of the backend holds the message: nothing is ingested and the caller tries again.
 *
 * <p>Neither the text, the file names nor the CDN links are logged.</p>
 */
public final class InboundFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboundFiles.class);
    /** The warning sign. */
    static final String WARNING = "\u26A0\uFE0F";

    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final AttachmentFetcher fetcher;
    private final Metrics metrics;

    public InboundFiles(@NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull AttachmentFetcher fetcher, @NotNull Metrics metrics) {
        this.backend = backend;
        this.discord = discord;
        this.fetcher = fetcher;
        this.metrics = metrics;
    }

    /**
     * @return the item to ingest, or empty when the backend could not take a file right now and the message must be tried again
     */
    public @NotNull Optional<IngestItem> toItem(@NotNull Ticket ticket, @NotNull IncomingMessage message) {
        boolean eligible = !message.bot() && (message.hasStaffRole() || ticket.player() != null && ticket.player() == message.authorId());
        List<Attachment> attachments = new ArrayList<>();
        boolean rejected = false;
        int files = 0;
        for (IncomingAttachment a : message.attachments()) {
            if (!"file".equals(a.kind()) || !eligible) {
                attachments.add(metadata(a, null, null));
                continue;
            }
            files++;
            if (files > AttachmentFetcher.MAX_FILES) {
                attachments.add(metadata(a, null, AttachmentFetcher.QUOTA));
                rejected = true;
                continue;
            }
            Outcome outcome = transfer(ticket, message, a);
            if (outcome == null) {
                return Optional.empty();
            }
            attachments.add(metadata(a, outcome.attachmentId(), outcome.rejected()));
            rejected |= outcome.rejected() != null;
        }
        if (rejected) {
            warn(message);
        }
        List<Embed> embeds = message.embeds().stream().map(e -> new Embed(e.type(), e.host())).toList();
        return Optional.of(new IngestItem(Long.toUnsignedString(message.messageId()), Long.toUnsignedString(message.authorId()), message.authorName(),
                message.bot(), message.hasStaffRole(), message.createdAt().toEpochMilli(),
                message.editedAt() == null ? null : message.editedAt().toEpochMilli(), DiscordMarkdown.toSource(message.text()), attachments, embeds));
    }

    private record Outcome(String attachmentId, String rejected) {
    }

    /** @return the outcome, or null when the backend is down and the message is held */
    private Outcome transfer(Ticket ticket, IncomingMessage message, IncomingAttachment a) {
        if (a.id() == null) {
            return new Outcome(null, AttachmentFetcher.FILE_NOT_ACCEPTED);
        }
        return switch (this.fetcher.fetch(a.url(), a.size())) {
            case AttachmentFetcher.Fetched.Rejected rejected -> new Outcome(null, rejected.code());
            case AttachmentFetcher.Fetched.Bytes bytes -> upload(ticket, message, a, bytes.data());
        };
    }

    private Outcome upload(Ticket ticket, IncomingMessage message, IncomingAttachment a, byte[] data) {
        Result<Uploaded> result = this.backend.uploadAttachment(ticket.id(), data, a.name() == null ? "file" : a.name(), a.id(),
                Long.toUnsignedString(message.authorId()));
        if (result instanceof Result.Ok<Uploaded> ok) {
            return new Outcome(ok.value().attachmentId(), null);
        }
        Result.Failed<Uploaded> failed = (Result.Failed<Uploaded>) result;
        if (failed.unavailable() || failed.status() == 503) {
            this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
            LOGGER.warn("Holding a message with a file: the backend could not take it (status {})", failed.status());
            return null;
        }
        return new Outcome(null, failed.code() == null ? AttachmentFetcher.FILE_NOT_ACCEPTED : failed.code());
    }

    private void warn(IncomingMessage message) {
        try {
            this.discord.react(new MessageRef(message.channelId(), message.messageId()), WARNING);
        } catch (GatewayException e) {
            this.metrics.increment(Counter.DISCORD_ERRORS);
            LOGGER.warn("Adding the warning reaction failed (permanent={})", e.permanent());
        }
    }

    private static Attachment metadata(IncomingAttachment a, String attachmentId, String rejected) {
        return new Attachment(a.kind(), a.id(), a.name(), a.contentType(), a.size(), a.width(), a.height(), attachmentId, rejected);
    }
}
