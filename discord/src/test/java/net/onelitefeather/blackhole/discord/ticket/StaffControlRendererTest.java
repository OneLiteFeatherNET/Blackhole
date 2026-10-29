package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.ControlButton;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Field;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds.Action;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.Decision;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.Phase;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.View;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StaffControlRendererTest {

    public static final String TICKET = "c0ffee00-0000-4000-8000-000000000001";
    public static final String CASE_REF = "9b2f0c3e-1111-4222-8333-444455556666";
    public static final String PANEL = "https://panel.example";
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    public static View view(Phase phase, boolean locked, Decision decision, String claimedBy) {
        return new View(TICKET, CASE_REF, PANEL, phase, locked, decision, claimedBy);
    }

    static MessageSpec render(View view) {
        return StaffControlRenderer.render(view, NOW);
    }

    static Optional<ControlButton> button(MessageSpec spec, Action action) {
        String id = StaffControlIds.id(action, TICKET);
        return spec.buttons().stream().filter(b -> id.equals(b.id())).findFirst();
    }

    static String field(MessageSpec spec, String name) {
        return spec.fields().stream().filter(f -> f.name().equals(name)).map(Field::value).findFirst().orElse(null);
    }

    @Test
    void titleNamesTheCaseReference() {
        assertTrue(render(view(Phase.OPEN, false, Decision.NONE, null)).title().contains(CASE_REF));
    }

    @Test
    void stateLineSaysOpenForAnOpenUndecidedTicket() {
        assertEquals("Open", field(render(view(Phase.OPEN, false, Decision.NONE, null)), "State"));
    }

    @Test
    void stateLineSaysLockedWhenTheChannelIsLocked() {
        assertEquals("Locked", field(render(view(Phase.OPEN, true, Decision.NONE, null)), "State"));
    }

    @Test
    void stateLineSaysAcceptedAfterAnAcceptance() {
        assertEquals("Decided: accepted", field(render(view(Phase.OPEN, false, Decision.ACCEPTED, null)), "State"));
    }

    @Test
    void claimedNameIsShownWhenSomeoneClaimedTheCase() {
        assertEquals("Alice", field(render(view(Phase.OPEN, false, Decision.NONE, "Alice")), "Claimed by"));
    }

    @Test
    void caseIsShownAsNotClaimedWhenNobodyHoldsIt() {
        assertEquals("Not claimed", field(render(view(Phase.OPEN, false, Decision.NONE, null)), "Claimed by"));
    }

    @Test
    void acceptAndRejectAreEnabledWhileUndecided() {
        MessageSpec spec = render(view(Phase.OPEN, false, Decision.NONE, null));
        assertFalse(button(spec, Action.ACCEPT).orElseThrow().disabled());
        assertFalse(button(spec, Action.REJECT).orElseThrow().disabled());
    }

    @Test
    void acceptAndRejectAreDisabledAfterADecision() {
        MessageSpec spec = render(view(Phase.OPEN, false, Decision.REJECTED, null));
        assertTrue(button(spec, Action.ACCEPT).orElseThrow().disabled(), "accept after a decision");
        assertTrue(button(spec, Action.REJECT).orElseThrow().disabled(), "reject after a decision");
    }

    @Test
    void closeIsHiddenUntilTheAppealIsDecided() {
        assertTrue(button(render(view(Phase.OPEN, false, Decision.NONE, null)), Action.CLOSE).isEmpty());
    }

    @Test
    void closeIsShownOnceTheAppealIsDecided() {
        assertFalse(button(render(view(Phase.OPEN, false, Decision.ACCEPTED, null)), Action.CLOSE).orElseThrow().disabled());
    }

    @Test
    void lockIsShownOnlyWhileTheChannelIsUnlocked() {
        MessageSpec spec = render(view(Phase.OPEN, false, Decision.NONE, null));
        assertTrue(button(spec, Action.LOCK).isPresent());
        assertTrue(button(spec, Action.UNLOCK).isEmpty());
    }

    @Test
    void unlockIsShownOnlyWhenTheChannelIsLocked() {
        MessageSpec spec = render(view(Phase.OPEN, true, Decision.NONE, null));
        assertTrue(button(spec, Action.UNLOCK).isPresent());
        assertTrue(button(spec, Action.LOCK).isEmpty());
    }

    @Test
    void claimIsDisabledOnceTheTicketIsClosing() {
        assertTrue(button(render(view(Phase.CLOSING, false, Decision.ACCEPTED, null)), Action.CLAIM).orElseThrow().disabled());
    }

    @Test
    void footerCarriesTheMarkerOfTheTicket() {
        assertTrue(render(view(Phase.OPEN, false, Decision.NONE, null)).footer().endsWith(StaffControlRenderer.marker(TICKET)));
    }
}
