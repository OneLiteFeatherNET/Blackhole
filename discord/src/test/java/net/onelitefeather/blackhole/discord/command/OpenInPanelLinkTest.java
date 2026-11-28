package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.ControlButton;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.Decision;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRenderer.Phase;
import net.onelitefeather.blackhole.discord.ticket.StaffControlRendererTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenInPanelLinkTest {

    /** A Ban ID in the shape players see it; it must never reach the message. */
    private static final String BAN_ID = "K7M2-9QXA";

    private static ControlButton link(MessageSpec spec) {
        return spec.buttons().stream().filter(b -> b.url() != null).findFirst().orElseThrow();
    }

    @Test
    void linkPointsAtTheCaseInThePanel() {
        MessageSpec spec = StaffControlRenderer.render(
                StaffControlRendererTest.view(Phase.OPEN, false, Decision.NONE, null), Instant.EPOCH);
        assertEquals(StaffControlRendererTest.PANEL + "/cases/" + StaffControlRendererTest.CASE_REF, link(spec).url());
    }

    @Test
    void linkIsShownWhateverTheStateOfTheTicket() {
        MessageSpec spec = StaffControlRenderer.render(
                StaffControlRendererTest.view(Phase.CLOSED, true, Decision.REJECTED, "Alice"), Instant.EPOCH);
        assertTrue(link(spec).url().endsWith("/cases/" + StaffControlRendererTest.CASE_REF));
    }

    @Test
    void noBanIdAppearsInTheLinkOrInAnyButtonOfTheMessage() {
        MessageSpec spec = StaffControlRenderer.render(
                StaffControlRendererTest.view(Phase.OPEN, false, Decision.ACCEPTED, "Alice"), Instant.EPOCH);
        for (ControlButton button : spec.buttons()) {
            assertFalse(button.id() != null && button.id().contains(BAN_ID), "a button id carries a Ban ID");
            assertFalse(button.url() != null && button.url().contains(BAN_ID), "the link carries a Ban ID");
        }
        assertFalse(spec.footer().contains(BAN_ID) || spec.title().contains(BAN_ID), "the footer or title carries a Ban ID");
    }
}
