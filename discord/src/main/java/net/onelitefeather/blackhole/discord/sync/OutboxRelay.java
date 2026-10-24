package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.Outbox;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageRef;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxFile;
import net.onelitefeather.blackhole.discord.text.OutboundText;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend to Discord: posts what the portal and the panel wrote into the ticket channel, in case order, and
 * acknowledges each step so the backend's cursor moves. The outbox is the truth; the broker only says "look now".
 *
 * <p>Posting and acknowledging are two calls, so a crash between them leaves a posted message without a link. The relay
 * carries the case message's reference in its footer; before posting, the relay looks for it and links the message it
 * finds instead of posting a second one. Not thread safe: the caller holds the ticket lock.</p>
 */
public final class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
    static final int PAGE = 100;
    static final String SHARE_UNAVAILABLE = "Evidence share unavailable";
    static final String SHARE_WITHDRAWN = "Evidence share withdrawn";
    static final String FILE_UNAVAILABLE = "File unavailable";
    private static final int MAX_PAGES = 20;

    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final Metrics metrics;

    public OutboxRelay(@NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull Metrics metrics) {
        this.backend = backend;
        this.discord = discord;
        this.metrics = metrics;
    }

    /** @return the footer reference of a case message, {@code ref} and its first eight characters */
    public static @NotNull String ref(@NotNull String messageId) {
        return "ref " + messageId.substring(0, Math.min(8, messageId.length()));
    }

    /** @return true when the outbox is drained, false when something stopped it (an outage, a Discord error, a very long backlog) */
    public boolean relay(@NotNull Ticket ticket) {
        Long channel = ticket.channel();
        if (channel == null) {
            return true;
        }
        long after = ticket.relayedSeq();
        for (int page = 0; page < MAX_PAGES; page++) {
            Result<Outbox> result = this.backend.outbox(ticket.id(), after, PAGE);
            if (result instanceof Result.Failed<Outbox> failed) {
                this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
                LOGGER.warn("Reading the outbox failed with status {}", failed.status());
                return false;
            }
            Outbox outbox = ((Result.Ok<Outbox>) result).value();
            if (outbox.items().isEmpty()) {
                return true;
            }
            for (OutboxItem item : outbox.items()) {
                if (!apply(ticket, channel, item)) {
                    return false;
                }
                after = item.seq();
            }
        }
        return false;
    }

    private boolean apply(Ticket ticket, long channel, OutboxItem item) {
        try {
            switch (item.kind()) {
                case ADD -> {
                    String ref = ref(item.messageId());
                    MessageRef posted = this.discord.find(channel, ref).orElse(null);
                    if (posted == null) {
                        // The bytes come first: when the store or scanner is down nothing is posted, and the next pass starts clean.
                        List<File> files = fetch(ticket, item.files());
                        if (files == null) {
                            return false;
                        }
                        posted = postMessage(channel, label(item.authorKind()), item.text(), ref, item.messageId());
                        for (File file : files) {
                            if (file.data() == null) {
                                this.discord.notice(channel, FILE_UNAVAILABLE);
                            } else {
                                this.discord.postFile(channel, null, file.name(), file.data());
                            }
                        }
                    }
                    return acknowledge(ticket, item, item.messageId(), Long.toString(posted.messageId()));
                }
                case EDIT -> {
                    if (item.discordMessageId() != null) {
                        this.discord.editRelay(new MessageRef(channel, Long.parseUnsignedLong(item.discordMessageId())),
                                String.join("\n\n", OutboundText.chunks(item.text())));
                    }
                    return acknowledge(ticket, item, null, null);
                }
                case SHARE -> {
                    return share(ticket, channel, item);
                }
                case SHARE_REVOKED -> {
                    if (item.discordMessageId() != null) {
                        this.discord.deleteMessage(new MessageRef(channel, Long.parseUnsignedLong(item.discordMessageId())));
                    }
                    this.discord.notice(channel, SHARE_WITHDRAWN);
                    return acknowledge(ticket, item, null, null);
                }
                case DELETE, REDACT -> {
                    if (item.discordMessageId() != null) {
                        this.discord.deleteMessage(new MessageRef(channel, Long.parseUnsignedLong(item.discordMessageId())));
                    }
                    return acknowledge(ticket, item, null, null);
                }
                default -> {
                    return acknowledge(ticket, item, null, null);
                }
            }
        } catch (GatewayException e) {
            this.metrics.increment(Counter.DISCORD_ERRORS);
            LOGGER.warn("Relaying into a ticket channel failed (permanent={})", e.permanent());
            return false;
        }
    }

    /** The message as labelled posts of at most 2000 characters; the first carries the reference by which a restart finds it again. */
    private MessageRef postMessage(long channel, String label, String text, String ref, String messageId) throws GatewayException {
        List<String> chunks = OutboundText.chunks(text);
        if (chunks.isEmpty()) {
            chunks = List.of("");
        }
        MessageRef first = this.discord.postRelay(channel, label, chunks.getFirst(), ref);
        for (int i = 1; i < chunks.size(); i++) {
            this.discord.postRelay(channel, label, chunks.get(i), "more " + messageId.substring(0, Math.min(8, messageId.length())) + " #" + i);
        }
        return first;
    }

    /** A share: an image as a labelled file, an excerpt as a code block, else the line that says it is unavailable. */
    private boolean share(Ticket ticket, long channel, OutboxItem item) throws GatewayException {
        MessageRef posted;
        if (!item.files().isEmpty()) {
            List<File> files = fetch(ticket, item.files().subList(0, 1));
            if (files == null) {
                return false;
            }
            File file = files.getFirst();
            posted = file.data() == null
                    ? this.discord.postPlain(channel, SHARE_UNAVAILABLE)
                    : this.discord.postFile(channel, "Evidence shared: " + OutboundText.literal(file.name()), file.name(), file.data());
        } else if (item.text() != null && !item.text().isBlank()) {
            List<String> blocks = OutboundText.codeBlocks(item.text());
            posted = this.discord.postPlain(channel, blocks.getFirst());
            for (int i = 1; i < blocks.size(); i++) {
                this.discord.postPlain(channel, blocks.get(i));
            }
        } else {
            posted = this.discord.postPlain(channel, SHARE_UNAVAILABLE);
        }
        return acknowledge(ticket, item, item.messageId(), Long.toString(posted.messageId()));
    }

    /** A file fetched for the channel; {@code data} is null when the backend says it is not visible. */
    private record File(String name, byte[] data) {
    }

    /** @return the files in order, or null when the backend is down (the caller holds the item and retries on the next pass) */
    private List<File> fetch(Ticket ticket, List<OutboxFile> wanted) {
        List<File> files = new ArrayList<>();
        for (OutboxFile file : wanted) {
            String name = file.name() == null || file.name().isBlank() ? "file" : file.name();
            Result<byte[]> result = this.backend.fetchAttachment(ticket.id(), file.attachmentId());
            if (result instanceof Result.Ok<byte[]> ok) {
                files.add(new File(name, ok.value()));
                continue;
            }
            Result.Failed<byte[]> failed = (Result.Failed<byte[]>) result;
            if (failed.unavailable() || failed.status() == 503) {
                this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
                LOGGER.warn("Fetching a file for a ticket channel failed with status {}; holding", failed.status());
                return null;
            }
            files.add(new File(name, null));
        }
        return files;
    }

    private boolean acknowledge(Ticket ticket, OutboxItem item, String messageId, String discordMessageId) {
        Result<Long> result = this.backend.linkRelay(ticket.id(), item.seq(), messageId, discordMessageId);
        if (result instanceof Result.Failed<Long> failed) {
            this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
            LOGGER.warn("Acknowledging a relay failed with status {}", failed.status());
            return false;
        }
        this.metrics.increment(Counter.RELAYS);
        return true;
    }

    private static String label(AuthorKind kind) {
        if (kind == null) {
            return "Message";
        }
        return switch (kind) {
            case STAFF -> "Moderation team";
            case PLAYER -> "Player (portal)";
            case BOT, EXTERNAL -> "Other";
        };
    }
}
