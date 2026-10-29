package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseReference;
import net.onelitefeather.blackhole.backend.discord.dto.StaffTicketDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffDelegation;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The staff lock of the case's appeal ticket. A lock sets the freeze with source {@code STAFF}; only a staff unlock lifts it, and a
 * leak freeze is never lifted here. A repeated value is a no-op that answers the current state.
 */
@Singleton
public class StaffTicketService {

    /** Event type of a changed freeze; the subject is the ticket id. Carries no Ban ID and no Discord id. */
    public static final String FROZEN_EVENT = "discord.ticket_frozen";

    private final CaseReference references;
    private final DiscordTicketStore store;
    private final StaffDelegation delegation;
    private final StaffAuditRecorder audit;
    private final DomainEventPublisher events;
    private final Clock clock;

    public StaffTicketService(PunishmentRepository punishments, DiscordTicketStore store, StaffDelegation delegation,
                              StaffAuditRecorder audit, DomainEventPublisher events, Clock clock) {
        this.references = new CaseReference(punishments);
        this.store = store;
        this.delegation = delegation;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @return {@link StaffTicketDTO.Response}, or an error: 400 (onBehalfOf from a non-bot), 403 (delegation refused), 404 (unknown case or
     * no open ticket), 409 {@code FREEZE_BY_LEAK}
     */
    public StaffTicketDTO freeze(String reference, StaffTicketDTO.FreezeRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message(), null);
        }
        StaffDelegation.Outcome delegated = this.delegation.attribute(actor, request.onBehalfOf());
        if (delegated instanceof StaffDelegation.Outcome.Refused refused) {
            return error(refused.reason() == StaffDelegation.Reason.NOT_DISCORD ? CaseErrorKind.VALIDATION : CaseErrorKind.FORBIDDEN,
                    refused.message(), null);
        }
        Attribution by = ((StaffDelegation.Outcome.Allowed) delegated).attribution();
        PunishmentEntity punishment = found.punishment();
        DiscordTicket ticket = openTicket(punishment);
        if (ticket == null) {
            return error(CaseErrorKind.NOT_FOUND, "the case has no open appeal ticket", null);
        }
        if (request.frozen()) {
            if (ticket.frozen()) {
                return response(ticket);
            }
            return changed(ticket, true, DiscordTicket.FREEZE_STAFF, by, actor, punishment);
        }
        if (!ticket.frozen()) {
            return response(ticket);
        }
        if (DiscordTicket.FREEZE_LEAK.equals(ticket.freezeSource())) {
            return new StaffTicketDTO.Error("the channel was locked by the leak check; staff cannot unlock it", ErrorKind.CONFLICT,
                    "FREEZE_BY_LEAK");
        }
        return changed(ticket, false, null, by, actor, punishment);
    }

    @Nullable
    private DiscordTicket openTicket(PunishmentEntity punishment) {
        List<DiscordTicket> live = this.store.liveTicketsOf(punishment.getIdentifier());
        return live.isEmpty() ? null : live.getFirst();
    }

    private StaffTicketDTO changed(DiscordTicket ticket, boolean frozen, @Nullable String source, Attribution by, Actor actor,
                                   PunishmentEntity punishment) {
        DiscordTicket after = this.store.setFrozen(ticket.id(), frozen, source, this.clock.millis()).orElse(ticket);
        this.events.publish(FROZEN_EVENT, after.id().toString(),
                Map.of("ticketId", after.id().toString(), "frozen", after.frozen()), failure -> { });
        if (!by.source().equals(by.recordedByOid())) {
            this.audit.record(by, actor, AuditAction.TICKET_FREEZE, AuditTarget.ofCase(PunishmentShortId.display(punishment.getShortId())));
        }
        return response(after);
    }

    private static StaffTicketDTO response(DiscordTicket ticket) {
        return new StaffTicketDTO.Response(ticket.frozen(), ticket.frozen() ? ticket.freezeSource() : null);
    }

    private static StaffTicketDTO.Error error(CaseErrorKind kind, String message, @Nullable String code) {
        ErrorKind mapped = switch (kind) {
            case NOT_FOUND -> ErrorKind.NOT_FOUND;
            case FORBIDDEN -> ErrorKind.FORBIDDEN;
            case CONFLICT -> ErrorKind.CONFLICT;
            default -> ErrorKind.VALIDATION;
        };
        return new StaffTicketDTO.Error(message, mapped, code);
    }
}
