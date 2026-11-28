package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.ControlButton;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.ControlStyle;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Field;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds.Action;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The staff control message of a ticket channel: the case, its state, who holds it and the buttons the state allows. It is a view
 * of the backend's state, never a state of its own: it is drawn from what the backend says at the time.
 */
public final class StaffControlRenderer {

    /** Where the ticket is in its life, as the bot sees it. */
    public enum Phase {
        OPEN, CLOSING, CLOSED
    }

    /** The appeal's decision so far. */
    public enum Decision {
        NONE, ACCEPTED, REJECTED
    }

    /**
     * What the message shows.
     *
     * @param ticketId      the ticket the buttons act on
     * @param caseReference the case, as the panel link names it (the punishment identifier, never a Ban ID)
     * @param panelUrl      the panel base URL without a trailing slash
     * @param phase         where the ticket is
     * @param locked        whether the channel is locked by staff
     * @param decision      the appeal's decision so far
     * @param claimedBy     the display name of the staff member who holds the case, or null
     */
    public record View(@NotNull String ticketId, @NotNull String caseReference, @NotNull String panelUrl, @NotNull Phase phase,
                       boolean locked, @NotNull Decision decision, @Nullable String claimedBy) {
    }

    private static final String FOOTER_PREFIX = "blackhole-staff:";
    private static final int COLOR = 0x5865F2;

    private StaffControlRenderer() {
    }

    /** @return the footer marker by which the bot finds its control message of a ticket again */
    public static @NotNull String marker(@NotNull String ticketId) {
        return FOOTER_PREFIX + ticketId;
    }

    /** @return the panel link of a case: {@code <panelUrl>/cases/<reference>} */
    public static @NotNull String panelLink(@NotNull String panelUrl, @NotNull String caseReference) {
        return panelUrl + "/cases/" + caseReference;
    }

    /** Draws the control message. Accept and Reject stay visible but disabled after a decision; Close appears only after it. */
    public static @NotNull MessageSpec render(@NotNull View view, @NotNull Instant timestamp) {
        boolean open = view.phase() == Phase.OPEN;
        boolean closed = view.phase() == Phase.CLOSED;
        boolean decided = view.decision() != Decision.NONE;
        List<ControlButton> buttons = new ArrayList<>();
        buttons.add(press(view, Action.CLAIM, "Claim", ControlStyle.SECONDARY, !open));
        buttons.add(press(view, Action.ACCEPT, "Accept", ControlStyle.SUCCESS, decided || !open));
        buttons.add(press(view, Action.REJECT, "Reject", ControlStyle.DANGER, decided || !open));
        if (view.locked()) {
            buttons.add(press(view, Action.UNLOCK, "Unlock", ControlStyle.SECONDARY, closed));
        } else {
            buttons.add(press(view, Action.LOCK, "Lock", ControlStyle.SECONDARY, closed));
        }
        if (decided) {
            buttons.add(press(view, Action.CLOSE, "Close", ControlStyle.DANGER, !open));
        }
        buttons.add(ControlButton.link(panelLink(view.panelUrl(), view.caseReference()), "Open in panel"));
        List<Field> fields = List.of(
                new Field("State", state(view), true),
                new Field("Claimed by", view.claimedBy() == null ? "Not claimed" : view.claimedBy(), true));
        return new MessageSpec(null, "Appeal case " + view.caseReference(), COLOR, fields, marker(view.ticketId()), timestamp,
                null, null, buttons);
    }

    private static ControlButton press(View view, Action action, String label, ControlStyle style, boolean disabled) {
        return ControlButton.press(StaffControlIds.id(action, view.ticketId()), label, style, disabled);
    }

    private static String state(View view) {
        if (view.phase() == Phase.CLOSING) {
            return "Closing";
        }
        if (view.phase() == Phase.CLOSED) {
            return "Closed";
        }
        if (view.decision() == Decision.ACCEPTED) {
            return "Decided: accepted";
        }
        if (view.decision() == Decision.REJECTED) {
            return "Decided: rejected";
        }
        return view.locked() ? "Locked" : "Open";
    }
}
