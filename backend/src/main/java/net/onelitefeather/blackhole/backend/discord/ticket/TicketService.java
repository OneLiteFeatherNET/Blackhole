package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.AppealProtocolGenerator;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolStore;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordTicketProperties;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO.Code;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Discord ticket of an appeal: opening it with a private Ban ID, the state machine of the channel's life and the gates that keep a
 * channel from being archived or deleted before its protocol is stored. Expected failures come back as {@link DiscordTicketDTO.Error}.
 *
 * <p>Every change is one SQL statement ({@link DiscordTicketStore}); {@code @Transactional} is unusable here. Domain events
 * ({@code discord.ticket_*}) go out after the write, in a try/catch, and carry ids and the hashed owner, never a Ban ID, a Discord id or
 * message text. Nothing here logs or emits a Discord user id or a Ban ID.</p>
 */
@Singleton
public class TicketService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketService.class);

    /** The routing keys of the domain events. */
    public static final String OPENED_EVENT = "discord.ticket_opened";
    public static final String CHANNEL_READY_EVENT = "discord.ticket_channel_ready";
    public static final String CLOSED_EVENT = "discord.ticket_closed";
    public static final String ARCHIVED_EVENT = "discord.ticket_archived";
    public static final String DELETED_EVENT = "discord.ticket_deleted";

    private static final String NOT_ELIGIBLE = "the Ban ID cannot be used to open a ticket";
    private static final Pattern DISCORD_ID = Pattern.compile("^\\d{15,20}$");
    private static final Pattern SNOWFLAKE = Pattern.compile("^\\d{1,19}$");
    private static final int MAX_NAME = 128;

    private final DiscordTicketStore store;
    private final BanIdService banIds;
    private final PunishmentRepository punishments;
    private final TicketAppeals appeals;
    private final CaseReader reader;
    private final CaseChanges changes;
    private final ProtocolStore protocols;
    private final AppealProtocolGenerator generator;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final DiscordTicketProperties properties;

    public TicketService(
            DiscordTicketStore store, BanIdService banIds, PunishmentRepository punishments, TicketAppeals appeals, CaseReader reader,
            CaseChanges changes, ProtocolStore protocols, AppealProtocolGenerator generator, DomainEventPublisher events, Clock clock,
            DiscordTicketProperties properties
    ) {
        this.store = store;
        this.banIds = banIds;
        this.punishments = punishments;
        this.appeals = appeals;
        this.reader = reader;
        this.changes = changes;
        this.protocols = protocols;
        this.generator = generator;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
    }

    // ---- open ----

    /**
     * Opens a ticket for the Discord user who typed a valid Ban ID. A wrong, unknown, leaked or already bound Ban ID all get the same
     * answer. The user who already has the ticket gets it again ({@code created: false}), so a retry or a second click is harmless.
     *
     * @return {@link DiscordTicketDTO.Opened}, or an error: 400 a malformed request or a missing or invalid statement, 404 the uniform
     * failure, 409 the player may not file yet ({@code availableFrom}), 409 a lost race for the ticket
     */
    public DiscordTicketDTO open(DiscordTicketDTO.OpenRequest request, Actor bot) {
        if (request.discordUserId() == null || !DISCORD_ID.matcher(request.discordUserId()).matches()) {
            return error(ErrorKind.VALIDATION, "discordUserId must be a Discord user id", null);
        }
        if (request.banId() == null || request.banId().isBlank()) {
            return error(ErrorKind.VALIDATION, "a Ban ID is required", null);
        }
        String name = request.displayName() == null || request.displayName().isBlank() ? null : cut(request.displayName().strip(), MAX_NAME);

        PunishmentEntity punishment = this.banIds.findIdentifier(request.banId()).flatMap(this.punishments::findById).orElse(null);
        if (punishment == null || leaked(punishment.getIdentifier())) {
            return notEligible();
        }
        String id = punishment.getIdentifier();
        long now = this.clock.millis();

        for (DiscordTicket live : this.store.liveTicketsOf(id)) {
            Participant owner = this.store.participant(live.ownerParticipantId()).orElse(null);
            if (owner == null || !request.discordUserId().equals(owner.discordUserId())) {
                return notEligible();
            }
            return new DiscordTicketDTO.Opened(toDto(live, owner), false, false);
        }

        TicketAppeals.Outcome outcome = this.appeals.attach(punishment, request.statement());
        UUID appealId;
        boolean filed;
        switch (outcome) {
            case TicketAppeals.Outcome.NotYet notYet -> {
                return new DiscordTicketDTO.Error("an appeal is not possible yet", ErrorKind.CONFLICT, Code.NOT_YET, notYet.availableFrom(), null, null);
            }
            case TicketAppeals.Outcome.StatementMissing ignored -> {
                return error(ErrorKind.VALIDATION, "a statement is required to file the appeal", Code.STATEMENT_REQUIRED);
            }
            case TicketAppeals.Outcome.StatementInvalid invalid -> {
                return error(ErrorKind.VALIDATION, invalid.reason(), Code.STATEMENT_REQUIRED);
            }
            case TicketAppeals.Outcome.Existing existing -> {
                appealId = existing.appealId();
                filed = false;
            }
            case TicketAppeals.Outcome.Filed created -> {
                appealId = created.appealId();
                filed = true;
            }
        }

        this.changes.ensureCase(id, now);
        Participant player = this.store.ensureParticipant(id, ParticipantKind.PLAYER, request.discordUserId(), name, false, now);
        DiscordTicket ticket;
        try {
            ticket = this.store.open(new DiscordTicketStore.NewTicket(UUID.randomUUID(), id, appealId, player.id(), now, bot.oid()));
        } catch (DiscordTicketStore.DuplicateTicketException raced) {
            DiscordTicket winner = this.store.liveTicketsOf(id).stream().filter(t -> t.ownerParticipantId().equals(player.id())).findFirst().orElse(null);
            return winner == null ? notEligible() : new DiscordTicketDTO.Opened(toDto(winner, player), false, filed);
        }
        publish(OPENED_EVENT, ticket, punishment);
        return new DiscordTicketDTO.Opened(toDto(ticket, player), true, filed);
    }

    // ---- reads ----

    /** @return {@link DiscordTicketDTO.Ticket} or 404 */
    public DiscordTicketDTO get(UUID id) {
        return this.store.ticket(id).<DiscordTicketDTO>map(this::toDto).orElseGet(TicketService::unknownTicket);
    }

    /** The tickets in the given states (all live and closing ones when none are given), oldest first. */
    public DiscordTicketDTO list(@Nullable Collection<TicketState> states) {
        Collection<TicketState> wanted = states == null || states.isEmpty()
                ? EnumSet.of(TicketState.PENDING_CHANNEL, TicketState.OPEN, TicketState.CLOSING, TicketState.CLOSED) : states;
        return new DiscordTicketDTO.TicketList(this.store.tickets(wanted, this.properties.listLimit()).stream().map(this::toDto).toList());
    }

    // ---- patch ----

    /**
     * Applies a change reported by the bot, in this order: the channel is gone, the freeze mirror, the channel id, the state.
     * A change that is already true is a no-op, so a retry is harmless.
     *
     * @return the {@link DiscordTicketDTO.Ticket} afterwards, or an error: 404, 409 {@code INVALID_STATE}, 409 {@code PROTOCOL_REQUIRED},
     * 409 {@code CHANNEL_TAKEN}
     */
    public DiscordTicketDTO patch(UUID id, DiscordTicketDTO.PatchRequest request, Actor bot) {
        DiscordTicket ticket = this.store.ticket(id).orElse(null);
        if (ticket == null) {
            return unknownTicket();
        }
        long now = this.clock.millis();

        if (Boolean.TRUE.equals(request.channelGone())) {
            if (ticket.state() != TicketState.DELETED) {
                ticket = this.store.deleted(id, now, bot.oid()).orElse(ticket);
                publish(DELETED_EVENT, ticket);
            }
            return toDto(this.store.ticket(id).orElse(ticket));
        }
        if (request.frozen() != null && request.frozen() != ticket.frozen()) {
            ticket = this.store.setFrozen(id, request.frozen(), now).orElse(ticket);
        }
        if (request.channelId() != null) {
            DiscordTicketDTO.Error failed = channel(ticket, request);
            if (failed != null) {
                return failed;
            }
            ticket = this.store.ticket(id).orElse(ticket);
        }
        if (request.state() != null && request.state() != ticket.state()) {
            DiscordTicketDTO.Error failed = move(ticket, request.state(), bot, now);
            if (failed != null) {
                return failed;
            }
            ticket = this.store.ticket(id).orElse(ticket);
        }
        return toDto(ticket);
    }

    private DiscordTicketDTO.Error channel(DiscordTicket ticket, DiscordTicketDTO.PatchRequest request) {
        if (request.channelId().equals(ticket.channelId())) {
            return null;
        }
        if (!SNOWFLAKE.matcher(request.channelId()).matches()) {
            return error(ErrorKind.VALIDATION, "channelId must be a Discord id", null);
        }
        if (ticket.state() != TicketState.PENDING_CHANNEL) {
            return error(ErrorKind.CONFLICT, "the channel of a ticket is reported once, while it is pending", Code.INVALID_STATE);
        }
        try {
            Optional<DiscordTicket> ready = this.store.channelReady(ticket.id(), request.channelId(), request.guildId(), this.clock.millis());
            ready.ifPresent(opened -> publish(CHANNEL_READY_EVENT, opened));
        } catch (DiscordTicketStore.ChannelTakenException taken) {
            return error(ErrorKind.CONFLICT, "another ticket already has this channel", Code.CHANNEL_TAKEN);
        }
        return null;
    }

    private DiscordTicketDTO.Error move(DiscordTicket ticket, TicketState target, Actor bot, long now) {
        UUID id = ticket.id();
        switch (target) {
            case CLOSING -> {
                return transition(id, Set.of(TicketState.OPEN), TicketState.CLOSING, now, target);
            }
            case CLOSED -> {
                // Only for a close that could not be proven complete: the proven one goes through the close route.
                return transition(id, Set.of(TicketState.OPEN, TicketState.CLOSING), TicketState.CLOSED, now, target);
            }
            case ARCHIVED -> {
                if (ticket.state() != TicketState.CLOSED) {
                    return invalidState(ticket.state(), target);
                }
                ProtocolVersion stored = this.protocols.active(ticket.appealId()).orElse(null);
                if (stored == null || !ticket.transcriptComplete()) {
                    return protocolRequired("a stored protocol and a verified transcript are required before the channel is archived");
                }
                Optional<DiscordTicket> archived = this.store.archive(id, stored.version(), now, bot.oid());
                archived.ifPresent(done -> publish(ARCHIVED_EVENT, done));
                return null;
            }
            case DELETED -> {
                if (ticket.state() != TicketState.ARCHIVED && ticket.state() != TicketState.CLOSED) {
                    return invalidState(ticket.state(), target);
                }
                if (!ticket.transcriptComplete() || this.protocols.active(ticket.appealId()).isEmpty() || !this.generator.isCurrent(ticket.appealId())) {
                    return protocolRequired("a current stored protocol is required before the channel is deleted; call protocol/ensure first");
                }
                Optional<DiscordTicket> deleted = this.store.deleted(id, now, bot.oid());
                deleted.ifPresent(done -> publish(DELETED_EVENT, done));
                return null;
            }
            default -> {
                return invalidState(ticket.state(), target);
            }
        }
    }

    private DiscordTicketDTO.Error transition(UUID id, Set<TicketState> from, TicketState to, long now, TicketState requested) {
        if (this.store.transition(id, from, to, now).isPresent()) {
            return null;
        }
        TicketState current = this.store.ticket(id).map(DiscordTicket::state).orElse(null);
        return current == to ? null : invalidState(current, requested);
    }

    // ---- close and protocol ----

    /**
     * The completeness proof: accepted only when the count and the last message id equal the backend's own tally of the channel's inbound
     * messages. Records {@code ticket.transcript_complete}, then generates and stores the protocol in the same request (forced, no settle
     * delay) and answers its version. Idempotent: repeating it with the same proof records nothing twice and names the same version.
     *
     * @return {@link DiscordTicketDTO.Closed}, or an error: 400 a malformed proof, 404, 409 {@code TRANSCRIPT_MISMATCH} (no event, no
     * protocol), 409 {@code INVALID_STATE}, 503 when the protocol could not be stored (the event stays, repeat the call)
     */
    public DiscordTicketDTO close(UUID id, DiscordTicketDTO.CloseRequest request, Actor bot) {
        DiscordTicket ticket = this.store.ticket(id).orElse(null);
        if (ticket == null) {
            return unknownTicket();
        }
        if (ticket.state() == TicketState.PENDING_CHANNEL || ticket.state() == TicketState.DELETED) {
            return invalidState(ticket.state(), TicketState.CLOSED);
        }
        Long last = snowflake(request.lastMessageId());
        if (request.count() < 0 || last == null) {
            return error(ErrorKind.VALIDATION, "count is not negative and lastMessageId is a Discord id", null);
        }

        long now = this.clock.millis();
        boolean recorded = this.store.complete(id, request.count(), last, now, bot.oid());
        DiscordTicket current = this.store.ticket(id).orElse(ticket);
        if (!recorded && !(current.transcriptComplete() && current.transcriptCount() != null && current.transcriptCount() == request.count()
                && Objects.equals(current.transcriptLastMessageId() == null ? 0L : current.transcriptLastMessageId(), last))) {
            DiscordTicketStore.Tally tally = this.store.tally(id);
            return new DiscordTicketDTO.Error("the count or the last message id differs from the recorded messages", ErrorKind.CONFLICT,
                    Code.TRANSCRIPT_MISMATCH, null, tally.count(), Long.toString(tally.lastMessageId()));
        }

        AppealProtocolGenerator.Result result = this.generator.generate(current.appealId(), true);
        switch (result.outcome()) {
            case STORED, UNCHANGED -> {
                if (recorded) {
                    publish(CLOSED_EVENT, current);
                }
                return new DiscordTicketDTO.Closed(toDto(current), result.version(), result.outcome().name());
            }
            case NOT_DECIDED -> {
                if (recorded) {
                    publish(CLOSED_EVENT, current);
                }
                return new DiscordTicketDTO.Closed(toDto(current), null, result.outcome().name());
            }
            case NOT_FOUND -> {
                return unknownTicket();
            }
            case TICKET_INCOMPLETE -> {
                return protocolRequired("the transcript is not verified complete");
            }
            default -> {
                // FAILED, TOO_LARGE, NOT_SETTLED: the event stays and the bot repeats the close.
                return error(ErrorKind.UNAVAILABLE, "the protocol could not be stored; repeat the close request", null);
            }
        }
    }

    /**
     * Regenerates the protocol when the conversation changed since the stored version (a late edit in the archived channel) and answers
     * the current version. The bot calls it before it deletes a channel.
     *
     * @return {@link DiscordTicketDTO.ProtocolEnsured}, or an error: 404, 409 {@code PROTOCOL_REQUIRED} (appeal undecided, transcript not
     * verified), 503
     */
    public DiscordTicketDTO ensureProtocol(UUID id) {
        DiscordTicket ticket = this.store.ticket(id).orElse(null);
        if (ticket == null) {
            return unknownTicket();
        }
        AppealProtocolGenerator.Result result = this.generator.generate(ticket.appealId(), true);
        return switch (result.outcome()) {
            case STORED, UNCHANGED -> new DiscordTicketDTO.ProtocolEnsured(result.version(), result.outcome() == AppealProtocolGenerator.Outcome.STORED);
            case NOT_DECIDED, TICKET_INCOMPLETE -> protocolRequired("the appeal is not decided or the transcript is not verified complete");
            case NOT_FOUND -> unknownTicket();
            default -> error(ErrorKind.UNAVAILABLE, "the protocol could not be stored; try again", null);
        };
    }

    // ---- relay ----

    /**
     * The player-visible events to relay after {@code afterSeq} (the ticket's own cursor when {@code null}).
     *
     * @return {@link DiscordTicketDTO.Outbox} or 404
     */
    public DiscordTicketDTO outbox(UUID id, @Nullable Long afterSeq, @Nullable Integer limit) {
        DiscordTicket ticket = this.store.ticket(id).orElse(null);
        if (ticket == null) {
            return unknownTicket();
        }
        int page = limit == null ? this.properties.outboxPageSize() : Math.max(1, Math.min(limit, this.properties.outboxMaxPageSize()));
        long after = afterSeq == null ? ticket.relayedSeq() : Math.max(0, afterSeq);
        List<DiscordTicketDTO.OutboxItem> items = this.store.outbox(id, after, page).stream()
                .map(item -> new DiscordTicketDTO.OutboxItem(item.seq(), item.kind(), item.messageId(), item.authorKind(), item.channel(),
                        item.text(), item.discordMessageId() == null ? null : Long.toString(item.discordMessageId()), item.occurredAt()))
                .toList();
        return new DiscordTicketDTO.Outbox(items, ticket.relayedSeq());
    }

    /**
     * Reports a relay; the cursor only moves forward and linking the same Discord message twice changes nothing.
     *
     * @return {@link DiscordTicketDTO.Linked}, or an error: 400 half a link or a malformed id, 404
     */
    public DiscordTicketDTO link(UUID id, DiscordTicketDTO.LinkRequest request) {
        if ((request.messageId() == null) != (request.discordMessageId() == null)) {
            return error(ErrorKind.VALIDATION, "messageId and discordMessageId come together", null);
        }
        Long discordMessageId = null;
        if (request.discordMessageId() != null) {
            discordMessageId = snowflake(request.discordMessageId());
            if (discordMessageId == null) {
                return error(ErrorKind.VALIDATION, "discordMessageId must be a Discord id", null);
            }
        }
        if (request.seq() < 0) {
            return error(ErrorKind.VALIDATION, "seq is not negative", null);
        }
        return this.store.acknowledge(id, request.seq(), request.messageId(), discordMessageId, this.clock.millis())
                .<DiscordTicketDTO>map(DiscordTicketDTO.Linked::new).orElseGet(TicketService::unknownTicket);
    }

    // ---- helpers ----

    private DiscordTicketDTO.Ticket toDto(DiscordTicket ticket) {
        Participant owner = this.store.participant(ticket.ownerParticipantId()).orElse(null);
        return toDto(ticket, owner);
    }

    private DiscordTicketDTO.Ticket toDto(DiscordTicket ticket, @Nullable Participant owner) {
        Integer version = this.protocols.active(ticket.appealId()).map(ProtocolVersion::version).orElse(null);
        return new DiscordTicketDTO.Ticket(ticket.id(), ticket.ticketNo(), ticket.state(), ticket.appealId(), ticket.channelId(), ticket.guildId(),
                owner == null ? null : owner.discordUserId(), leaked(ticket.punishmentIdentifier()), ticket.frozen(),
                Long.toString(ticket.ingestedCursor()), ticket.relayedSeq(), ticket.transcriptComplete(), ticket.transcriptCount(),
                ticket.transcriptLastMessageId() == null ? null : Long.toString(ticket.transcriptLastMessageId()), version, ticket.createdAt());
    }

    private boolean leaked(String punishmentIdentifier) {
        return this.reader.find(punishmentIdentifier).filter(CaseReader.CaseRow::leaked).isPresent();
    }

    private void publish(String type, DiscordTicket ticket) {
        PunishmentEntity punishment = this.punishments.findById(ticket.punishmentIdentifier()).orElse(null);
        if (punishment != null) {
            publish(type, ticket, punishment);
        }
    }

    private void publish(String type, DiscordTicket ticket, PunishmentEntity punishment) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("ticketId", ticket.id().toString());
            payload.put("ticketNo", ticket.ticketNo());
            payload.put("appealId", ticket.appealId().toString());
            payload.put("punishmentIdentifier", punishment.getIdentifier());
            payload.put("owner", punishment.getOwner());
            payload.put("occurredAt", this.clock.millis());
            this.events.publish(type, payload);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {} for ticket {}", type, ticket.id(), e);
        }
    }

    private static @Nullable Long snowflake(@Nullable String value) {
        if (value == null || value.isBlank() || value.equals("0")) {
            return 0L;
        }
        if (!SNOWFLAKE.matcher(value).matches()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooBig) {
            return null;
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static DiscordTicketDTO.Error notEligible() {
        return error(ErrorKind.NOT_FOUND, NOT_ELIGIBLE, Code.NOT_ELIGIBLE);
    }

    private static DiscordTicketDTO.Error unknownTicket() {
        return error(ErrorKind.NOT_FOUND, "there is no such ticket", null);
    }

    private static DiscordTicketDTO.Error invalidState(@Nullable TicketState from, TicketState to) {
        return error(ErrorKind.CONFLICT, "a ticket cannot move from " + from + " to " + to, Code.INVALID_STATE);
    }

    private static DiscordTicketDTO.Error protocolRequired(String message) {
        return error(ErrorKind.CONFLICT, message, Code.PROTOCOL_REQUIRED);
    }

    private static DiscordTicketDTO.Error error(ErrorKind kind, String message, @Nullable Code code) {
        return DiscordTicketDTO.Error.of(message, kind, code);
    }
}
