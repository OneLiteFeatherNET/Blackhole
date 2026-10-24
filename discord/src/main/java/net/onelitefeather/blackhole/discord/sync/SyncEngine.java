package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestBatch;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.config.BotConfig.TicketSettings;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingMessage;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.sched.Scheduler;
import net.onelitefeather.blackhole.discord.ticket.ArchiveSweeper;
import net.onelitefeather.blackhole.discord.ticket.PermissionSelfCheck;
import net.onelitefeather.blackhole.discord.ticket.StaffAccessReconciler;
import net.onelitefeather.blackhole.discord.ticket.TicketLifecycle;
import net.onelitefeather.blackhole.discord.ticket.TicketRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The conductor of the ticket channels. It listens to Discord (live messages, edits, deletes, deleted channels), polls the
 * backend on the sync interval, and treats the RabbitMQ events as a hint to poll now. The backend's cursors are the truth:
 * a lost or duplicated event, a restart or an outage changes nothing but the delay.
 *
 * <p>Everything runs on the one {@link Scheduler} thread (events are handed off with {@link Scheduler#execute}), and every
 * ticket mutation holds the lifecycle lock, which the slash commands share.</p>
 *
 * <p>Messages outside the ticket channels are not this class's business; they go to the sinks given at construction (the leak
 * scanner), and messages of the bot itself are never ingested.</p>
 */
public final class SyncEngine implements DiscordGateway.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncEngine.class);
    private static final Set<State> LIVE = EnumSet.of(State.PENDING_CHANNEL, State.OPEN, State.CLOSING, State.CLOSED, State.ARCHIVED);
    static final int MAX_RETRIES = 500;
    private static final Duration SWEEP_EVERY = Duration.ofHours(1);
    private static final Duration SELF_CHECK_EVERY = Duration.ofHours(1);

    private record Pending(String ticketId, IngestBatch batch) {
    }

    private final TicketSettings settings;
    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final TicketLifecycle lifecycle;
    private final OutboxRelay relay;
    private final CatchUp catchUp;
    private final StaffAccessReconciler staff;
    private final ArchiveSweeper sweeper;
    private final PermissionSelfCheck selfCheck;
    private final TicketRegistry registry;
    private final Scheduler scheduler;
    private final Metrics metrics;
    private final Consumer<IncomingMessage> otherMessages;
    private final Consumer<IncomingMessage> otherEdits;

    private final Deque<Pending> retries = new ArrayDeque<>();
    private final Set<String> needsCatchUp = new HashSet<>();
    private final AtomicBoolean pollQueued = new AtomicBoolean();
    private final AtomicBoolean reconcileQueued = new AtomicBoolean();
    private boolean startupPending = true;

    @SuppressWarnings("java:S107")
    public SyncEngine(@NotNull TicketSettings settings, @NotNull BackendGateway backend, @NotNull DiscordGateway discord,
                      @NotNull TicketLifecycle lifecycle, @NotNull OutboxRelay relay, @NotNull CatchUp catchUp,
                      @NotNull StaffAccessReconciler staff, @NotNull ArchiveSweeper sweeper, @NotNull PermissionSelfCheck selfCheck,
                      @NotNull TicketRegistry registry, @NotNull Scheduler scheduler, @NotNull Metrics metrics,
                      @NotNull Consumer<IncomingMessage> otherMessages, @NotNull Consumer<IncomingMessage> otherEdits) {
        this.settings = settings;
        this.backend = backend;
        this.discord = discord;
        this.lifecycle = lifecycle;
        this.relay = relay;
        this.catchUp = catchUp;
        this.staff = staff;
        this.sweeper = sweeper;
        this.selfCheck = selfCheck;
        this.registry = registry;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.otherMessages = otherMessages;
        this.otherEdits = otherEdits;
    }

    /** Starts the timers: the cursor poll, the access reconcile, the archive sweep and the permission check. */
    public void start() {
        this.scheduler.repeat(this.settings.syncInterval(), this.settings.syncInterval(), this::poll);
        this.scheduler.repeat(this.settings.reconcileInterval(), this.settings.reconcileInterval(), this::reconcile);
        this.scheduler.repeat(Duration.ofMinutes(1), SWEEP_EVERY, this::sweep);
        this.scheduler.repeat(SELF_CHECK_EVERY, SELF_CHECK_EVERY, this.selfCheck::run);
    }

    // ---- hints from the broker ----

    /** The broker says something changed in a case: poll now (many hints make one poll). */
    public void hint() {
        this.metrics.increment(Counter.BROKER_HINTS);
        requestPoll();
    }

    private void requestPoll() {
        if (this.pollQueued.compareAndSet(false, true)) {
            this.scheduler.execute(() -> {
                this.pollQueued.set(false);
                poll();
            });
        }
    }

    /** The broker says a staff link was removed: reconcile channel access now. */
    public void reconcileHint() {
        this.metrics.increment(Counter.BROKER_HINTS);
        if (this.reconcileQueued.compareAndSet(false, true)) {
            this.scheduler.execute(() -> {
                this.reconcileQueued.set(false);
                reconcile();
            });
        }
    }

    // ---- Discord events ----

    @Override
    public void onReady() {
        this.scheduler.execute(() -> {
            this.startupPending = true;
            this.selfCheck.run();
            poll();
            reconcile();
        });
    }

    @Override
    public void onMessage(@NotNull IncomingMessage message) {
        this.scheduler.execute(() -> live(message, false));
    }

    @Override
    public void onMessageEdit(@NotNull IncomingMessage message) {
        this.scheduler.execute(() -> live(message, true));
    }

    @Override
    public void onMessageDelete(long channelId, long messageId) {
        this.scheduler.execute(() -> {
            Ticket ticket = this.registry.byChannel(channelId);
            if (ticket != null) {
                send(ticket, IngestBatch.delete(Long.toUnsignedString(messageId)), true);
            }
        });
    }

    @Override
    public void onChannelDelete(long channelId) {
        this.scheduler.execute(() -> {
            this.lifecycle.lock().lock();
            try {
                if (this.lifecycle.expectedDeletion(channelId)) {
                    this.registry.remove(channelId);
                    return;
                }
                Ticket ticket = this.registry.byChannel(channelId);
                if (ticket != null && ticket.state() != State.ARCHIVED) {
                    this.lifecycle.channelGone(ticket);
                } else if (ticket != null) {
                    // An archived channel deleted by hand: the sweeper records it after making sure the protocol is current.
                    this.registry.remove(channelId);
                }
            } finally {
                this.lifecycle.lock().unlock();
            }
        });
    }

    private void live(IncomingMessage message, boolean edit) {
        this.lifecycle.lock().lock();
        try {
            Ticket ticket = this.registry.byChannel(message.channelId());
            if (ticket == null) {
                if (message.categoryId() == this.settings.ticketCategoryId() || message.categoryId() == this.settings.archiveCategoryId()) {
                    // A ticket channel this bot has not learned about yet; the poll registers it and reads what it missed.
                    this.startupPending = true;
                    requestPoll();
                    return;
                }
                (edit ? this.otherEdits : this.otherMessages).accept(message);
                return;
            }
            if (message.self() || ticket.state() == State.DELETED) {
                return;
            }
            send(ticket, IngestBatch.of(List.of(CatchUp.toItem(message))), edit);
        } finally {
            this.lifecycle.lock().unlock();
        }
    }

    /** Sends a live batch; when the backend cannot take it, edits and deletes are queued and a new message falls back to the cursor catch-up. */
    private void send(Ticket ticket, IngestBatch batch, boolean queueOnFailure) {
        Result<?> result = this.backend.ingest(ticket.id(), batch);
        if (result instanceof Result.Ok<?>) {
            this.metrics.increment(Counter.INGESTS);
            return;
        }
        this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
        Result.Failed<?> failed = (Result.Failed<?>) result;
        if (!failed.unavailable()) {
            LOGGER.warn("The backend refused a live message (status {})", failed.status());
            return;
        }
        if (queueOnFailure) {
            if (this.retries.size() >= MAX_RETRIES) {
                this.retries.removeFirst();
            }
            this.retries.addLast(new Pending(ticket.id(), batch));
        } else {
            this.needsCatchUp.add(ticket.id());
        }
    }

    // ---- the poll ----

    /** Reads the tickets from the backend and brings every channel up to date. Safe to call at any time and as often as wanted. */
    public void poll() {
        this.lifecycle.lock().lock();
        try {
            Result<List<Ticket>> result = this.backend.tickets(LIVE);
            if (result instanceof Result.Failed<List<Ticket>> failed) {
                this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
                LOGGER.warn("Listing tickets failed with status {}", failed.status());
                return;
            }
            List<Ticket> tickets = ((Result.Ok<List<Ticket>>) result).value();
            this.registry.replaceAll(tickets);
            boolean startup = this.startupPending;
            if (startup) {
                tickets.stream().filter(t -> t.channel() != null).forEach(t -> this.needsCatchUp.add(t.id()));
                this.startupPending = false;
            }
            drainRetries();
            for (Ticket ticket : tickets) {
                try {
                    step(ticket, startup);
                } catch (RuntimeException e) {
                    LOGGER.error("Syncing a ticket failed: {}", e.getClass().getName());
                }
            }
        } finally {
            this.lifecycle.lock().unlock();
        }
    }

    private void step(Ticket ticket, boolean startup) {
        if (ticket.state() == State.PENDING_CHANNEL) {
            this.lifecycle.ensureChannel(ticket);
            return;
        }
        Long channel = ticket.channel();
        if (channel == null) {
            return;
        }
        if (!this.discord.channelExists(channel)) {
            if (ticket.state() != State.ARCHIVED) {
                this.lifecycle.channelGone(ticket);
            }
            return;
        }
        if (this.needsCatchUp.contains(ticket.id()) && this.catchUp.ingestSince(ticket)) {
            this.needsCatchUp.remove(ticket.id());
        }
        switch (ticket.state()) {
            case OPEN -> {
                this.relay.relay(ticket);
                this.lifecycle.syncFreeze(ticket);
                this.lifecycle.closeIfDecided(ticket);
            }
            case CLOSING -> this.lifecycle.close(ticket);
            case CLOSED -> {
                if (ticket.transcriptComplete()) {
                    this.lifecycle.finishClosed(ticket, null);
                } else if (startup) {
                    this.lifecycle.close(ticket);
                }
            }
            default -> {
            }
        }
    }

    private void drainRetries() {
        List<Pending> due = new ArrayList<>(this.retries);
        this.retries.clear();
        for (int i = 0; i < due.size(); i++) {
            Pending pending = due.get(i);
            Result<?> result = this.backend.ingest(pending.ticketId(), pending.batch());
            if (result instanceof Result.Failed<?> failed && failed.unavailable()) {
                // Still down: keep this one and everything after it, in order.
                this.retries.addAll(due.subList(i, due.size()));
                return;
            }
            if (result instanceof Result.Ok<?>) {
                this.metrics.increment(Counter.INGESTS);
            }
        }
    }

    // ---- timers ----

    private void reconcile() {
        this.lifecycle.lock().lock();
        try {
            this.staff.reconcileAll();
        } finally {
            this.lifecycle.lock().unlock();
        }
    }

    private void sweep() {
        this.sweeper.sweep();
    }

    /** @return how many edits and deletes wait for the backend (for tests and the health report) */
    public int pendingRetries() {
        this.lifecycle.lock().lock();
        try {
            return this.retries.size();
        } finally {
            this.lifecycle.lock().unlock();
        }
    }
}
