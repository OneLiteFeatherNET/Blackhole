package net.onelitefeather.blackhole.discord.leak;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.LeakResult;
import net.onelitefeather.blackhole.discord.config.BotConfig.LeakMode;
import net.onelitefeather.blackhole.discord.config.BotConfig.LeakSettings;
import net.onelitefeather.blackhole.discord.config.BotConfig.TicketSettings;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingMessage;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageRef;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.sched.Scheduler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Ban IDs posted in public channels and has them removed, without acting on look-alike text.
 *
 * <p>A candidate is {@code #} followed by eight Crockford base32 characters (case-insensitive, with at most one space or hyphen
 * in the middle) at a word boundary, at most five per message. The {@code #} is required: a bare eight character token is every
 * English word and hex number, so false positives would be the common case; bare tokens are not detected, and that gap is accepted.
 * The scanner never decides anything itself. It sends the normalised candidates to the backend, which looks them up; only when the
 * backend reports a current Ban ID is the message deleted, and a short notice without any identifier is posted. A candidate that is no
 * Ban ID causes nothing.</p>
 *
 * <p>Scope: new and edited messages (history is not scanned) in every channel or thread where {@code @everyone} can view, except the
 * ticket and archive categories; {@code LIST} restricts it to configured channels or categories and {@code OFF} sends nothing to the
 * backend. When the backend cannot be reached a candidate is retried with a growing delay for {@link #RETRY_WINDOW} and then dropped with
 * a counter; the number of candidates waiting is bounded. No message content, candidate or Discord id is ever logged.</p>
 */
public final class LeakScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeakScanner.class);

    /** Candidates: the {@code #}, then 4 + 4 characters of the alphabet without U ({@code I L O} are read as 1, 1 and 0) at a word boundary. */
    static final Pattern CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])#([0-9A-TV-Z]{4})[ -]?([0-9A-TV-Z]{4})(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE);
    public static final int MAX_CANDIDATES = 5;
    public static final Duration RETRY_WINDOW = Duration.ofMinutes(5);
    public static final int MAX_PENDING = 1000;
    static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    static final Duration MAX_RETRY = Duration.ofMinutes(1);

    private record Job(MessageRef message, List<String> candidates, String poster, boolean posterIsBot, boolean listed, Instant firstSeen) {
    }

    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Metrics metrics;
    private final LeakSettings settings;
    private final TicketSettings tickets;
    private final AtomicInteger pending = new AtomicInteger();
    private final Set<Long> permissionLogged = new HashSet<>();

    public LeakScanner(@NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull Scheduler scheduler, @NotNull Clock clock,
                       @NotNull Metrics metrics, @NotNull LeakSettings settings, @NotNull TicketSettings tickets) {
        this.backend = backend;
        this.discord = discord;
        this.scheduler = scheduler;
        this.clock = clock;
        this.metrics = metrics;
        this.settings = settings;
        this.tickets = tickets;
    }

    /**
     * Extracts the candidates of a text: normalised (upper case, no separator, {@code I L O} read as {@code 1 1 0}), without
     * duplicates, in order of appearance, at most {@value #MAX_CANDIDATES}.
     */
    public static @NotNull List<String> candidates(@NotNull String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = CANDIDATE.matcher(text);
        while (found.size() < MAX_CANDIDATES && matcher.find()) {
            String code = (matcher.group(1) + matcher.group(2)).toUpperCase(java.util.Locale.ROOT)
                    .replace('I', '1').replace('L', '1').replace('O', '0');
            found.add(code);
        }
        return new ArrayList<>(found);
    }

    /** A new message in a guild channel that is not a ticket channel. */
    public void onMessage(@NotNull IncomingMessage message) {
        scan(message);
    }

    /** An edited message: scanned like a new one, because the ID was visible for as long as it stood. */
    public void onEdit(@NotNull IncomingMessage message) {
        scan(message);
    }

    /** Whether a message is in scope, and as which kind of channel. */
    Optional<Boolean> listedScope(IncomingMessage message) {
        if (this.settings.mode() == LeakMode.OFF || message.self()) {
            return Optional.empty();
        }
        if (message.categoryId() == this.tickets.ticketCategoryId() || message.categoryId() == this.tickets.archiveCategoryId()) {
            return Optional.empty();
        }
        return switch (this.settings.mode()) {
            case PUBLIC -> message.publicChannel() ? Optional.of(false) : Optional.empty();
            case LIST -> this.settings.channelIds().contains(message.channelId()) || this.settings.channelIds().contains(message.categoryId())
                    ? Optional.of(true) : Optional.empty();
            case OFF -> Optional.empty();
        };
    }

    private void scan(IncomingMessage message) {
        Optional<Boolean> listed = listedScope(message);
        if (listed.isEmpty()) {
            return;
        }
        List<String> candidates = candidates(message.text());
        if (candidates.isEmpty()) {
            return;
        }
        if (this.pending.get() >= MAX_PENDING) {
            this.metrics.increment(Counter.LEAK_CANDIDATES_DROPPED);
            LOGGER.warn("The leak check queue is full; a message was not checked");
            return;
        }
        this.pending.incrementAndGet();
        Job job = new Job(new MessageRef(message.channelId(), message.messageId()), candidates,
                message.bot() ? null : Long.toUnsignedString(message.authorId()), message.bot(), listed.get(), this.clock.instant());
        this.scheduler.execute(() -> process(job, FIRST_RETRY));
    }

    private void process(Job job, Duration nextDelay) {
        Result<LeakResult> result = this.backend.leakCheck(job.candidates(), job.listed(), job.poster(), job.posterIsBot());
        if (result instanceof Result.Ok<LeakResult> ok) {
            this.pending.decrementAndGet();
            this.metrics.increment(Counter.LEAK_CHECKS);
            if (ok.value().matched() > 0) {
                remove(job);
            }
            return;
        }
        Result.Failed<LeakResult> failed = (Result.Failed<LeakResult>) result;
        this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
        if (!failed.unavailable()) {
            this.pending.decrementAndGet();
            this.metrics.increment(Counter.LEAK_CANDIDATES_DROPPED);
            LOGGER.warn("The backend refused a leak check (status {}); the message was not checked", failed.status());
            return;
        }
        if (!this.clock.instant().isBefore(job.firstSeen().plus(RETRY_WINDOW))) {
            this.pending.decrementAndGet();
            this.metrics.increment(Counter.LEAK_CANDIDATES_DROPPED);
            LOGGER.warn("The backend stayed unreachable for {} minutes; a message was dropped unchecked", RETRY_WINDOW.toMinutes());
            return;
        }
        Duration following = nextDelay.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : nextDelay.multipliedBy(2);
        this.scheduler.schedule(nextDelay, () -> process(job, following));
    }

    private void remove(Job job) {
        try {
            this.discord.deleteMessage(job.message());
            this.metrics.increment(Counter.LEAK_MESSAGES_REMOVED);
        } catch (GatewayException e) {
            this.metrics.increment(Counter.DISCORD_ERRORS);
            if (this.permissionLogged.add(job.message().channelId())) {
                LOGGER.error("A message with a ban code could not be removed from a scanned channel (permanent={}); "
                        + "grant the bot Manage Messages there", e.permanent());
            }
            return;
        }
        try {
            this.discord.notice(job.message().channelId(), "A message with a ban code was removed.\nEine Nachricht mit einem Bancode wurde entfernt.");
        } catch (GatewayException e) {
            this.metrics.increment(Counter.DISCORD_ERRORS);
        }
    }

    /** @return how many messages wait for the backend (for tests and the health report) */
    public int pending() {
        return this.pending.get();
    }
}
