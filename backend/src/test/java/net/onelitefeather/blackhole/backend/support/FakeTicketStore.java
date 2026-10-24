package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicket;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.Participant;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link DiscordTicketStore} over maps with the rules of the V18 tables that matter to the service: one live ticket per appeal and player, a
 * channel used once, a participant per Discord user. The completeness proof is checked against a tally the test sets.
 */
public final class FakeTicketStore implements DiscordTicketStore {

    public final Map<UUID, Participant> participants = new LinkedHashMap<>();
    public final Map<UUID, DiscordTicket> tickets = new LinkedHashMap<>();
    /** The case events a store call would append, as {@code type} strings in order. */
    public final List<String> appended = new ArrayList<>();
    /** What {@link #tally} answers. */
    public Tally tally = new Tally(0, 0, 0, 0);
    public List<OutboxItem> outbox = new ArrayList<>();
    public final Map<UUID, Long> acknowledged = new LinkedHashMap<>();
    private final AtomicLong numbers = new AtomicLong();

    @Override
    public Participant ensureParticipant(String punishmentIdentifier, ParticipantKind kind, String discordUserId, String displayName, boolean bot,
                                         long now) {
        Optional<Participant> existing = participantByDiscordUser(punishmentIdentifier, discordUserId);
        if (existing.isPresent()) {
            Participant old = existing.get();
            Participant updated = new Participant(old.id(), old.punishmentIdentifier(), kind == ParticipantKind.PLAYER ? kind : old.kind(),
                    old.discordUserId(), displayName != null ? displayName : old.displayName(), old.bot(), old.createdAt(), old.erasedAt());
            this.participants.put(updated.id(), updated);
            return updated;
        }
        Participant created = new Participant(UUID.randomUUID(), punishmentIdentifier, kind, discordUserId, displayName, bot, now, null);
        this.participants.put(created.id(), created);
        return created;
    }

    @Override
    public Optional<Participant> participant(UUID id) {
        return Optional.ofNullable(this.participants.get(id));
    }

    @Override
    public Optional<Participant> participantByDiscordUser(String punishmentIdentifier, String discordUserId) {
        return this.participants.values().stream()
                .filter(p -> p.punishmentIdentifier().equals(punishmentIdentifier) && discordUserId.equals(p.discordUserId())).findFirst();
    }

    @Override
    public boolean eraseParticipant(String punishmentIdentifier, UUID participantId, long now) {
        Participant p = this.participants.get(participantId);
        if (p == null || !p.punishmentIdentifier().equals(punishmentIdentifier) || p.erased()) {
            return false;
        }
        this.participants.put(participantId, new Participant(p.id(), p.punishmentIdentifier(), p.kind(), null, null, p.bot(), p.createdAt(), now));
        return true;
    }

    @Override
    public int erasePlayerParticipants(String punishmentIdentifier, long now) {
        int erased = 0;
        for (Participant p : List.copyOf(this.participants.values())) {
            if (p.kind() == ParticipantKind.PLAYER && p.punishmentIdentifier().equals(punishmentIdentifier) && eraseParticipant(punishmentIdentifier, p.id(), now)) {
                erased++;
            }
        }
        return erased;
    }

    @Override
    public Optional<DiscordTicket> ticket(UUID id) {
        return Optional.ofNullable(this.tickets.get(id));
    }

    @Override
    public List<DiscordTicket> tickets(Collection<TicketState> states, int limit) {
        return this.tickets.values().stream().filter(t -> states.contains(t.state())).limit(limit).toList();
    }

    @Override
    public List<DiscordTicket> liveTicketsOf(String punishmentIdentifier) {
        return this.tickets.values().stream().filter(t -> t.punishmentIdentifier().equals(punishmentIdentifier) && t.state().live()).toList();
    }

    @Override
    public DiscordTicket open(NewTicket ticket) {
        boolean duplicate = this.tickets.values().stream().anyMatch(t -> t.appealId().equals(ticket.appealId())
                && t.ownerParticipantId().equals(ticket.ownerParticipantId()) && t.state().live());
        if (duplicate) {
            throw new DuplicateTicketException(null);
        }
        DiscordTicket created = new DiscordTicket(ticket.id(), this.numbers.incrementAndGet(), ticket.punishmentIdentifier(), ticket.appealId(),
                ticket.ownerParticipantId(), TicketState.PENDING_CHANNEL, null, null, false, 0, 0, null, null, null, ticket.now(), ticket.now());
        this.tickets.put(created.id(), created);
        this.appended.add("ticket.opened");
        return created;
    }

    @Override
    public Optional<DiscordTicket> channelReady(UUID id, String channelId, String guildId, long now) {
        if (this.tickets.values().stream().anyMatch(t -> channelId.equals(t.channelId()))) {
            throw new ChannelTakenException(null);
        }
        DiscordTicket t = this.tickets.get(id);
        if (t == null || t.state() != TicketState.PENDING_CHANNEL) {
            return Optional.empty();
        }
        return Optional.of(replace(t, TicketState.OPEN, channelId, guildId, t.frozen(), t.transcriptCompleteAt(), t.transcriptCount(), t.transcriptLastMessageId()));
    }

    @Override
    public Optional<DiscordTicket> transition(UUID id, Set<TicketState> from, TicketState to, long now) {
        DiscordTicket t = this.tickets.get(id);
        if (t == null || !from.contains(t.state())) {
            return Optional.empty();
        }
        return Optional.of(replace(t, to, t.channelId(), t.guildId(), t.frozen(), t.transcriptCompleteAt(), t.transcriptCount(), t.transcriptLastMessageId()));
    }

    @Override
    public Optional<DiscordTicket> setFrozen(UUID id, boolean frozen, long now) {
        DiscordTicket t = this.tickets.get(id);
        return t == null ? Optional.empty()
                : Optional.of(replace(t, t.state(), t.channelId(), t.guildId(), frozen, t.transcriptCompleteAt(), t.transcriptCount(), t.transcriptLastMessageId()));
    }

    @Override
    public Optional<DiscordTicket> archive(UUID id, int protocolVersion, long now, UUID recordedByOid) {
        DiscordTicket t = this.tickets.get(id);
        if (t == null || t.state() != TicketState.CLOSED) {
            return Optional.empty();
        }
        this.appended.add("ticket.archived");
        return Optional.of(replace(t, TicketState.ARCHIVED, t.channelId(), t.guildId(), t.frozen(), t.transcriptCompleteAt(), t.transcriptCount(),
                t.transcriptLastMessageId()));
    }

    @Override
    public Optional<DiscordTicket> deleted(UUID id, long now, UUID recordedByOid) {
        DiscordTicket t = this.tickets.get(id);
        if (t == null || t.state() == TicketState.DELETED) {
            return Optional.empty();
        }
        this.appended.add("ticket.channel_deleted");
        return Optional.of(replace(t, TicketState.DELETED, t.channelId(), t.guildId(), t.frozen(), t.transcriptCompleteAt(), t.transcriptCount(),
                t.transcriptLastMessageId()));
    }

    @Override
    public boolean complete(UUID id, int count, long lastMessageId, long now, UUID recordedByOid) {
        DiscordTicket t = this.tickets.get(id);
        if (t == null || t.transcriptComplete() || this.tally.count() != count || this.tally.lastMessageId() != lastMessageId) {
            return false;
        }
        TicketState next = t.state() == TicketState.OPEN || t.state() == TicketState.CLOSING ? TicketState.CLOSED : t.state();
        replace(t, next, t.channelId(), t.guildId(), t.frozen(), now, count, lastMessageId == 0 ? null : lastMessageId);
        this.appended.add("ticket.transcript_complete");
        return true;
    }

    @Override
    public Tally tally(UUID ticketId) {
        return this.tally;
    }

    @Override
    public List<OutboxItem> outbox(UUID ticketId, long afterSeq, int limit) {
        return this.outbox.stream().filter(item -> item.seq() > afterSeq).limit(limit).toList();
    }

    @Override
    public Optional<Long> acknowledge(UUID ticketId, long seq, UUID messageId, Long discordMessageId, long now) {
        DiscordTicket t = this.tickets.get(ticketId);
        if (t == null) {
            return Optional.empty();
        }
        long relayed = Math.max(t.relayedSeq(), seq);
        this.tickets.put(ticketId, new DiscordTicket(t.id(), t.ticketNo(), t.punishmentIdentifier(), t.appealId(), t.ownerParticipantId(), t.state(),
                t.channelId(), t.guildId(), t.frozen(), t.ingestedCursor(), relayed, t.transcriptCount(), t.transcriptLastMessageId(),
                t.transcriptCompleteAt(), t.createdAt(), now));
        this.acknowledged.put(ticketId, relayed);
        return Optional.of(relayed);
    }

    private DiscordTicket replace(DiscordTicket t, TicketState state, String channelId, String guildId, boolean frozen, Long completeAt, Integer count,
                                  Long lastId) {
        DiscordTicket changed = new DiscordTicket(t.id(), t.ticketNo(), t.punishmentIdentifier(), t.appealId(), t.ownerParticipantId(), state, channelId,
                guildId, frozen, t.ingestedCursor(), t.relayedSeq(), count, lastId, completeAt, t.createdAt(), t.updatedAt());
        this.tickets.put(t.id(), changed);
        return changed;
    }
}
