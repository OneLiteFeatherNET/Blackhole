package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationResult;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Closed;
import net.onelitefeather.blackhole.discord.backend.Tickets.Patch;
import net.onelitefeather.blackhole.discord.backend.Tickets.ProtocolEnsured;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.config.BotConfig.TicketSettings;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MemberAccess;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.NewChannel;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.sync.CatchUp;
import net.onelitefeather.blackhole.discord.sync.OutboxRelay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A ticket channel's life: creation (and finishing a creation that crashed half way), freeze and thaw, the close with its
 * rescan and completeness proof, the archive, and the delete.
 *
 * <p>Two rules protect the record: a channel is moved to the archive only once the backend named a stored protocol version
 * (it refuses the state otherwise), and an archived channel is deleted only after {@code protocol/ensure} confirmed a current
 * protocol. Every step is safe to repeat; whatever fails is simply tried again by the next poll. All entry points hold
 * {@link #lock()}, which the poll and the slash commands share, so a ticket is never changed by two threads at once.</p>
 */
public final class TicketLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketLifecycle.class);
    /** The ticket marker in a channel topic; the channel is found again by it after a crash. */
    static final String TOPIC_MARKER = "blackhole-ticket:";
    static final int MAX_RESCANS = 3;
    private static final Set<String> PENDING_APPEAL = Set.of("SUBMITTED", "ELIGIBLE_PENDING_REVIEW", "IN_REVIEW");
    static final Set<Permission> PLAYER_ALLOW = Set.of(Permission.VIEW, Permission.SEND, Permission.READ_HISTORY);
    static final Set<Permission> PLAYER_DENY = Set.of(Permission.ATTACH_FILES, Permission.EMBED_LINKS);
    static final Set<Permission> PLAYER_LOCKED_ALLOW = Set.of(Permission.VIEW, Permission.READ_HISTORY);
    static final Set<Permission> PLAYER_LOCKED_DENY = Set.of(Permission.SEND, Permission.ATTACH_FILES, Permission.EMBED_LINKS);
    private static final Set<Permission> BOT_ALLOW = Set.of(Permission.VIEW, Permission.SEND, Permission.READ_HISTORY, Permission.EMBED_LINKS);

    private final TicketSettings settings;
    private final long guildId;
    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final OutboxRelay relay;
    private final CatchUp catchUp;
    private final StaffAccessReconciler staff;
    private final TicketRegistry registry;
    private final Metrics metrics;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Long> deletedByUs = ConcurrentHashMap.newKeySet();
    private volatile Consumer<Ticket> channelReady = ticket -> { };

    /** Called once a ticket has its channel and the backend knows it: the staff control message is drawn here. */
    public void channelReady(@NotNull Consumer<Ticket> hook) {
        this.channelReady = hook;
    }

    public TicketLifecycle(@NotNull TicketSettings settings, long guildId, @NotNull BackendGateway backend, @NotNull DiscordGateway discord,
                           @NotNull OutboxRelay relay, @NotNull CatchUp catchUp, @NotNull StaffAccessReconciler staff,
                           @NotNull TicketRegistry registry, @NotNull Metrics metrics, @NotNull Clock clock) {
        this.settings = settings;
        this.guildId = guildId;
        this.backend = backend;
        this.discord = discord;
        this.relay = relay;
        this.catchUp = catchUp;
        this.staff = staff;
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The lock every ticket mutation holds. */
    public @NotNull ReentrantLock lock() {
        return this.lock;
    }

    /** @return whether the channel was deleted by this bot a moment ago (its delete event is then expected) */
    public boolean expectedDeletion(long channelId) {
        return this.deletedByUs.remove(channelId);
    }

    // ---- creation ----

    /**
     * Gives a {@code PENDING_CHANNEL} ticket its channel: finds the one a crashed run already made (by its topic) or creates it,
     * grants the player, the bot and the linked staff access, relays the case's earlier messages in order, and only then reports
     * the channel so the ticket becomes {@code OPEN}. Nothing here is lost on a crash, and a repeat never makes a second channel.
     *
     * @return the channel, empty when it could not be made yet (the next poll tries again)
     */
    public @NotNull Optional<Long> ensureChannel(@NotNull Ticket ticket) {
        this.lock.lock();
        try {
            if (ticket.state() != State.PENDING_CHANNEL) {
                return Optional.ofNullable(ticket.channel());
            }
            long channel;
            boolean fresh;
            Optional<Long> existing = this.discord.findChannelByTopic(
                    List.of(this.settings.ticketCategoryId(), this.settings.archiveCategoryId()), TOPIC_MARKER + ticket.id());
            if (existing.isPresent()) {
                channel = existing.get();
                fresh = false;
            } else {
                channel = this.discord.createChannel(new NewChannel(channelName(ticket), topic(ticket), this.settings.ticketCategoryId(),
                        initialMembers(ticket)));
                fresh = true;
            }
            Ticket withChannel = ticket.withChannel(channel);
            this.registry.put(withChannel);
            this.staff.reconcile(List.of(withChannel));
            if (fresh) {
                this.discord.notice(channel, ChannelTexts.welcome());
            }
            this.relay.relay(withChannel);
            Result<Ticket> patched = this.backend.patchTicket(ticket.id(), Patch.channel(Long.toUnsignedString(channel), Long.toUnsignedString(this.guildId)));
            if (patched instanceof Result.Ok<Ticket> ok) {
                this.registry.put(ok.value());
                this.metrics.increment(Counter.TICKETS_OPENED);
                this.channelReady.accept(ok.value());
                return Optional.of(channel);
            }
            this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
            LOGGER.warn("Reporting a ticket channel failed with status {}", ((Result.Failed<Ticket>) patched).status());
            return Optional.empty();
        } catch (GatewayException e) {
            discordFailed("creating a ticket channel", e);
            return Optional.empty();
        } finally {
            this.lock.unlock();
        }
    }

    static String channelName(Ticket ticket) {
        return String.format("appeal-%04d", ticket.ticketNo());
    }

    static String topic(Ticket ticket) {
        return TOPIC_MARKER + ticket.id() + " - Messages here are recorded with the appeal case. Staff need a linked Discord account (/link).";
    }

    private List<MemberAccess> initialMembers(Ticket ticket) {
        MemberAccess bot = new MemberAccess(this.discord.selfId(), BOT_ALLOW, Set.of());
        Long player = ticket.player();
        return player == null ? List.of(bot) : List.of(bot, new MemberAccess(player, PLAYER_ALLOW, PLAYER_DENY));
    }

    // ---- freeze and thaw ----

    /** Makes the channel match the ticket's leak state: leaked closes the player side, no longer leaked opens it again. */
    public void syncFreeze(@NotNull Ticket ticket) {
        this.lock.lock();
        try {
            Long channel = ticket.channel();
            Long player = ticket.player();
            if (channel == null || ticket.state() != State.OPEN) {
                return;
            }
            if (ticket.leaked() && !ticket.frozen()) {
                if (player != null) {
                    this.discord.setMemberAccess(channel, new MemberAccess(player, PLAYER_LOCKED_ALLOW, PLAYER_LOCKED_DENY));
                }
                if (this.backend.patchTicket(ticket.id(), Patch.frozen(true)) instanceof Result.Ok<Ticket> ok) {
                    this.registry.put(ok.value());
                    this.metrics.increment(Counter.FREEZES);
                    this.discord.notice(channel, ChannelTexts.frozen());
                }
            } else if (!ticket.leaked() && ticket.frozen()) {
                // The backend keeps a staff lock: a thaw that comes back still frozen opens nothing on the player side.
                if (this.backend.patchTicket(ticket.id(), Patch.frozen(false)) instanceof Result.Ok<Ticket> ok) {
                    this.registry.put(ok.value());
                    if (ok.value().frozen()) {
                        return;
                    }
                    if (player != null) {
                        this.discord.setMemberAccess(channel, new MemberAccess(player, PLAYER_ALLOW, PLAYER_DENY));
                    }
                    this.discord.notice(channel, ChannelTexts.thawed());
                }
            }
        } catch (GatewayException e) {
            discordFailed("freezing a ticket channel", e);
        } finally {
            this.lock.unlock();
        }
    }

    // ---- decision, close, archive ----

    /**
     * Shows a staff lock or unlock in the channel once the backend holds it: the player side closes or reopens. A leak freeze
     * still closes the player side on an unlock.
     */
    public void applyStaffLock(@NotNull Ticket ticket, boolean locked) {
        this.lock.lock();
        try {
            Long channel = ticket.channel();
            Long player = ticket.player();
            if (channel == null) {
                return;
            }
            if (player != null) {
                boolean closed = locked || ticket.leaked();
                this.discord.setMemberAccess(channel, closed
                        ? new MemberAccess(player, PLAYER_LOCKED_ALLOW, PLAYER_LOCKED_DENY)
                        : new MemberAccess(player, PLAYER_ALLOW, PLAYER_DENY));
            }
            this.discord.notice(channel, locked ? ChannelTexts.staffLocked() : ChannelTexts.staffUnlocked());
        } catch (GatewayException e) {
            discordFailed("showing a staff lock in a ticket channel", e);
        } finally {
            this.lock.unlock();
        }
    }

    /** Starts the close when the appeal behind an open ticket has been decided. */
    public void closeIfDecided(@NotNull Ticket ticket) {
        this.lock.lock();
        try {
            NotificationResult appeal = this.backend.appeal(ticket.appealId());
            if (appeal instanceof NotificationResult.Found found && !pending(found.notification())) {
                close(ticket);
            }
        } finally {
            this.lock.unlock();
        }
    }

    private static boolean pending(Notification appeal) {
        return PENDING_APPEAL.contains(appeal.status());
    }

    /**
     * Closes a ticket whose appeal is decided: relays the last messages (the decision), locks the player side, rescans the whole
     * channel and proves it complete. A mismatch triggers a fresh rescan, up to {@value #MAX_RESCANS} times; after that the ticket is
     * marked {@code CLOSED} without the completeness event, an incomplete-transcript metric is raised and the channel is kept. An
     * outage leaves the ticket {@code CLOSING}; the next poll resumes.
     */
    public void close(@NotNull Ticket ticket) {
        close(ticket, null);
    }

    /**
     * The same close, recorded under the staff member when {@code staffOid} is given (a staff member pressed Close in the channel).
     */
    public void close(@NotNull Ticket ticket, @Nullable String staffOid) {
        this.lock.lock();
        try {
            Long channel = ticket.channel();
            if (channel == null) {
                return;
            }
            if (!this.relay.relay(ticket)) {
                return;
            }
            Long player = ticket.player();
            if (player != null) {
                this.discord.setMemberAccess(channel, new MemberAccess(player, PLAYER_LOCKED_ALLOW, PLAYER_LOCKED_DENY));
            }
            if (ticket.state() == State.OPEN) {
                Result<Ticket> closing = this.backend.patchTicket(ticket.id(), Patch.state(State.CLOSING));
                if (closing instanceof Result.Failed<Ticket> failed) {
                    unavailable("starting the close", failed);
                    return;
                }
                this.registry.put(((Result.Ok<Ticket>) closing).value());
            }
            for (int attempt = 1; attempt <= MAX_RESCANS; attempt++) {
                Optional<CatchUp.Proof> proof = this.catchUp.rescan(ticket);
                if (proof.isEmpty()) {
                    return;
                }
                Result<Closed> closed = this.backend.close(ticket.id(), proof.get().count(), proof.get().lastMessageId(), staffOid);
                if (closed instanceof Result.Ok<Closed> ok) {
                    this.registry.put(ok.value().ticket());
                    finishClosed(ok.value().ticket(), ok.value().protocolVersion());
                    return;
                }
                Result.Failed<Closed> failed = (Result.Failed<Closed>) closed;
                if (!failed.is("TRANSCRIPT_MISMATCH")) {
                    unavailable("closing a ticket", failed);
                    return;
                }
            }
            this.metrics.increment(Counter.INCOMPLETE_TRANSCRIPTS);
            LOGGER.error("A ticket transcript could not be proven complete after {} rescans; the channel is kept", MAX_RESCANS);
            if (this.backend.patchTicket(ticket.id(), Patch.state(State.CLOSED)) instanceof Result.Ok<Ticket> ok) {
                this.registry.put(ok.value());
            }
        } catch (GatewayException e) {
            discordFailed("closing a ticket channel", e);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * A closed ticket with a verified transcript: makes sure a protocol version exists and archives. A ticket whose transcript is
     * not complete is left alone.
     */
    public void finishClosed(@NotNull Ticket ticket, Integer knownVersion) {
        this.lock.lock();
        try {
            if (!ticket.transcriptComplete()) {
                return;
            }
            Integer version = knownVersion;
            if (version == null) {
                Result<ProtocolEnsured> ensured = this.backend.ensureProtocol(ticket.id());
                if (ensured instanceof Result.Ok<ProtocolEnsured> ok && ok.value().stored()) {
                    version = ok.value().protocolVersion();
                } else if (ensured instanceof Result.Failed<ProtocolEnsured> failed) {
                    unavailable("ensuring the protocol", failed);
                }
            }
            if (version == null) {
                // No protocol version yet (undecided, or it could not be stored): never archive without one.
                return;
            }
            archive(ticket);
        } finally {
            this.lock.unlock();
        }
    }

    private void archive(Ticket ticket) {
        Long channel = ticket.channel();
        if (channel == null) {
            return;
        }
        try {
            this.discord.moveChannel(channel, this.settings.archiveCategoryId());
        } catch (GatewayException e) {
            discordFailed("moving a ticket channel to the archive", e);
            return;
        }
        Result<Ticket> archived = this.backend.patchTicket(ticket.id(), Patch.state(State.ARCHIVED));
        if (archived instanceof Result.Failed<Ticket> failed) {
            unavailable("archiving a ticket", failed);
            return;
        }
        this.registry.put(((Result.Ok<Ticket>) archived).value());
        this.metrics.increment(Counter.TICKETS_ARCHIVED);
        try {
            this.discord.notice(channel, ChannelTexts.archived(this.settings.deleteAfter(), Instant.now(this.clock)));
        } catch (GatewayException e) {
            discordFailed("posting the archive notice", e);
        }
    }

    // ---- channel gone, delete ----

    /** A live ticket whose channel was deleted by hand: tells the backend (the panel then shows the gap). */
    public void channelGone(@NotNull Ticket ticket) {
        this.lock.lock();
        try {
            Long channel = ticket.channel();
            if (this.backend.patchTicket(ticket.id(), Patch.gone()) instanceof Result.Ok<Ticket>) {
                if (channel != null) {
                    this.registry.remove(channel);
                }
                LOGGER.warn("A ticket channel was deleted before the ticket was closed; the backend records the gap");
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Deletes an archived channel once it is older than the configured delay, but only after {@code protocol/ensure} confirmed a
     * stored, current protocol and only for a verified transcript. A channel that is already gone is just reported.
     *
     * @return whether the ticket is deleted now
     */
    public boolean deleteIfDue(@NotNull Ticket ticket) {
        this.lock.lock();
        try {
            if (!ticket.transcriptComplete()) {
                return false;
            }
            Long channel = ticket.channel();
            boolean exists = channel != null && this.discord.channelExists(channel);
            if (exists) {
                Optional<Instant> last = this.discord.lastActivity(channel);
                if (last.isEmpty() || Instant.now(this.clock).isBefore(last.get().plus(this.settings.deleteAfter()))) {
                    return false;
                }
            }
            Result<ProtocolEnsured> ensured = this.backend.ensureProtocol(ticket.id());
            if (!(ensured instanceof Result.Ok<ProtocolEnsured> ok) || !ok.value().stored()) {
                if (ensured instanceof Result.Failed<ProtocolEnsured> failed) {
                    unavailable("ensuring the protocol before a delete", failed);
                }
                return false;
            }
            if (exists) {
                this.deletedByUs.add(channel);
                try {
                    this.discord.deleteChannel(channel);
                } catch (GatewayException e) {
                    this.deletedByUs.remove(channel);
                    discordFailed("deleting an archived channel", e);
                    return false;
                }
            }
            Result<Ticket> deleted = this.backend.patchTicket(ticket.id(), Patch.state(State.DELETED));
            if (deleted instanceof Result.Failed<Ticket> failed) {
                unavailable("recording a deleted channel", failed);
                return false;
            }
            if (channel != null) {
                this.registry.remove(channel);
            }
            this.metrics.increment(Counter.TICKETS_DELETED);
            return true;
        } catch (GatewayException e) {
            discordFailed("reading an archived channel", e);
            return false;
        } finally {
            this.lock.unlock();
        }
    }

    // ---- helpers ----

    private void unavailable(String what, Result.Failed<?> failed) {
        this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
        LOGGER.warn("The backend did not accept {} (status {}, code {})", what, failed.status(), failed.code());
    }

    private void discordFailed(String what, GatewayException e) {
        this.metrics.increment(Counter.DISCORD_ERRORS);
        LOGGER.warn("Discord refused {} (permanent={})", what, e.permanent());
    }
}
