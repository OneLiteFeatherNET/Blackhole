package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.CaseRead;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationResult;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffLink;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffOutcome;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.Verdict;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.sync.ControlMessageSync;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds.Action;
import net.onelitefeather.blackhole.discord.ticket.TicketLifecycle;
import net.onelitefeather.blackhole.discord.ticket.TicketRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The staff buttons of a ticket channel and its decision modals. Every press is checked against the ticket of its channel and the
 * staff role of the presser, then the presser is resolved to a linked staff member on the backend, which decides whether the action
 * may happen. A refused or failed action changes nothing; the control message is then drawn again from the backend's state.
 */
public final class StaffButtonHandler {

    static final String OUT_OF_DATE = "This control is out of date. Use the newest control message of this channel.";
    static final String STAFF_ONLY = "Only the moderation team can use these controls.";
    static final String LINK_HINT = "Your Discord account is not linked to a moderator account. Run /link with a code from the panel, then try again.";
    static final String RETRY = "The action did not happen: the backend could not be reached. Try again.";
    static final String DECIDE_FIRST = "Decide the appeal first (Accept or Reject). Only then can the ticket be closed.";
    static final String REJECT_NEEDS_MESSAGE = "A message to the player is required to reject an appeal.";
    static final String CLAIMED = "You hold this case now.";
    static final String DECIDED = "The decision is saved.";
    static final String CLOSING = "The ticket is being closed. The control message follows.";

    /** Where the answer to one interaction goes; exactly one {@code text} or {@code decisionModal} per interaction. */
    public interface Reply {
        /** An answer only the presser sees. */
        void text(@NotNull String text);

        /** Opens the decision modal of Accept or Reject on the ticket. */
        void decisionModal(@NotNull Action decision, @NotNull String ticketId);
    }

    /**
     * A press of a control button.
     *
     * @param channelId the channel the message is in
     * @param userId    the Discord user who pressed
     * @param customId  the button's id
     * @param staffRole whether the presser holds the staff role
     * @param reply     where the answer goes
     */
    public record Press(long channelId, long userId, @NotNull String customId, boolean staffRole, @NotNull Reply reply) {
    }

    /**
     * A submitted decision modal.
     *
     * @param message the text the modal held; null or blank for none
     */
    public record Submit(long channelId, long userId, @NotNull Action decision, @NotNull String ticketId, @Nullable String message,
                         boolean staffRole, @NotNull Reply reply) {
    }

    private final BackendGateway backend;
    private final TicketRegistry registry;
    private final TicketLifecycle lifecycle;
    private final ControlMessageSync control;

    public StaffButtonHandler(@NotNull BackendGateway backend, @NotNull TicketRegistry registry, @NotNull TicketLifecycle lifecycle,
                              @NotNull ControlMessageSync control) {
        this.backend = backend;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.control = control;
    }

    /** A control button was pressed. Accept and Reject only open their modal; the rest act now. */
    public void onPress(@NotNull Press press) {
        Optional<StaffControlIds.Press> parsed = StaffControlIds.parse(press.customId());
        Ticket ticket = this.registry.byChannel(press.channelId());
        if (parsed.isEmpty() || ticket == null || !ticket.id().equals(parsed.get().ticketId())) {
            press.reply().text(OUT_OF_DATE);
            return;
        }
        if (!press.staffRole()) {
            press.reply().text(STAFF_ONLY);
            return;
        }
        Action action = parsed.get().action();
        if (action == Action.ACCEPT || action == Action.REJECT) {
            press.reply().decisionModal(action, ticket.id());
            return;
        }
        Optional<String> staff = staffOid(press.userId(), press.reply());
        if (staff.isEmpty()) {
            return;
        }
        switch (action) {
            case CLAIM -> claim(ticket, staff.get(), press.reply());
            case LOCK -> freeze(ticket, true, staff.get(), press.reply());
            case UNLOCK -> freeze(ticket, false, staff.get(), press.reply());
            case CLOSE -> close(ticket, staff.get(), press.reply());
            default -> press.reply().text(OUT_OF_DATE);
        }
    }

    /** A decision modal was submitted. A rejection needs a message; the backend checks the text and may refuse it. */
    public void onDecision(@NotNull Submit submit) {
        Ticket ticket = this.registry.byChannel(submit.channelId());
        if (ticket == null || !ticket.id().equals(submit.ticketId())) {
            submit.reply().text(OUT_OF_DATE);
            return;
        }
        if (!submit.staffRole()) {
            submit.reply().text(STAFF_ONLY);
            return;
        }
        String message = submit.message() == null ? "" : submit.message().strip();
        if (submit.decision() == Action.REJECT && message.isEmpty()) {
            submit.reply().text(REJECT_NEEDS_MESSAGE);
            return;
        }
        Optional<String> staff = staffOid(submit.userId(), submit.reply());
        if (staff.isEmpty()) {
            return;
        }
        Verdict verdict = submit.decision() == Action.ACCEPT ? Verdict.ACCEPT : Verdict.REJECT;
        StaffOutcome outcome = this.backend.review(ticket.appealId(), verdict, message.isEmpty() ? null : message, staff.get());
        answer(outcome, DECIDED, ticket, submit.reply());
    }

    private void claim(Ticket ticket, String staffOid, Reply reply) {
        String caseReference = caseReference(ticket).orElse(null);
        if (caseReference == null) {
            reply.text(RETRY);
            return;
        }
        if (!(this.backend.caseSnapshot(caseReference) instanceof CaseRead.Found found)) {
            reply.text(RETRY);
            return;
        }
        answer(this.backend.claimCase(caseReference, found.snapshot().version(), staffOid), CLAIMED, ticket, reply);
    }

    private void freeze(Ticket ticket, boolean locked, String staffOid, Reply reply) {
        String caseReference = caseReference(ticket).orElse(null);
        if (caseReference == null) {
            reply.text(RETRY);
            return;
        }
        StaffOutcome outcome = this.backend.setFreeze(caseReference, locked, staffOid);
        if (outcome instanceof StaffOutcome.Done) {
            Ticket fresh = fresh(ticket);
            this.lifecycle.applyStaffLock(fresh, locked);
            this.control.redraw(fresh);
            reply.text(locked ? "The channel is locked." : "The channel is unlocked.");
            return;
        }
        answer(outcome, "", ticket, reply);
    }

    private void close(Ticket ticket, String staffOid, Reply reply) {
        if (!(this.backend.appeal(ticket.appealId()) instanceof NotificationResult.Found found) || pending(found.notification())) {
            reply.text(DECIDE_FIRST);
            return;
        }
        this.lifecycle.close(fresh(ticket), staffOid);
        reply.text(CLOSING);
        this.control.redraw(fresh(ticket));
    }

    /** Answers a backend outcome and draws the control message again from the backend's state. */
    private void answer(StaffOutcome outcome, String done, Ticket ticket, Reply reply) {
        switch (outcome) {
            case StaffOutcome.Done ignored -> {
                reply.text(done);
                this.control.redraw(fresh(ticket));
            }
            case StaffOutcome.Refused refused -> {
                reply.text(refused.text() == null || refused.text().isBlank() ? RETRY : refused.text());
                this.control.redraw(fresh(ticket));
            }
            case StaffOutcome.Unavailable unavailable -> reply.text(RETRY);
        }
    }

    /** Resolves the presser to a staff member the backend would let act; otherwise tells them what to do and returns empty. */
    private Optional<String> staffOid(long discordUserId, Reply reply) {
        StaffLink link = this.backend.staffLink(Long.toString(discordUserId));
        return switch (link) {
            case StaffLink.Linked linked when linked.eligible() -> Optional.of(linked.oid());
            case StaffLink.Linked linked -> hint(reply);
            case StaffLink.NotLinked notLinked -> hint(reply);
            case StaffLink.Unavailable unavailable -> {
                reply.text(RETRY);
                yield Optional.empty();
            }
        };
    }

    private static Optional<String> hint(Reply reply) {
        reply.text(LINK_HINT);
        return Optional.empty();
    }

    /** The case of the ticket's appeal: the punishment identifier, which is what the panel link and the case reads use. */
    private Optional<String> caseReference(Ticket ticket) {
        if (this.backend.appeal(ticket.appealId()) instanceof NotificationResult.Found found) {
            return Optional.ofNullable(found.notification().punishmentIdentifier());
        }
        return Optional.empty();
    }

    /** The ticket as the backend holds it now; the registry is updated with it. Falls back to the given one when the backend is unreachable. */
    private Ticket fresh(Ticket ticket) {
        if (this.backend.ticket(ticket.id()) instanceof Result.Ok<Ticket> ok) {
            this.registry.put(ok.value());
            return ok.value();
        }
        return ticket;
    }

    private static boolean pending(Notification appeal) {
        return switch (appeal.status()) {
            case "SUBMITTED", "ELIGIBLE_PENDING_REVIEW", "IN_REVIEW" -> true;
            default -> false;
        };
    }
}
