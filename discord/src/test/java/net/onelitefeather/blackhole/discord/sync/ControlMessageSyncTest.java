package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlMessageSyncTest {

    private Harness h;
    private FakeTicketBackend.Rec rec;
    private CaseEventHandler events;

    @BeforeEach
    void setUp() {
        this.h = new Harness();
        ControlMessageSync sync = this.h.enableStaffActions();
        this.h.ready();
        this.rec = this.h.openTicketWithChannel(1);
        this.events = new CaseEventHandler(this.h.engine, sync::onCaseEvent);
    }

    private static byte[] caseEvent(String type, String punishmentIdentifier) {
        return ("{\"eventId\":\"e\",\"eventType\":\"" + type + "\",\"payload\":{\"punishmentIdentifier\":\"" + punishmentIdentifier + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private long edits() {
        return this.h.guild.calls.stream().filter("edit"::equals).count();
    }

    @Test
    void controlMessageIsPostedOnceWhenTheChannelIsReady() {
        long controls = this.h.guild.messages(this.h.channelOf(this.rec)).stream()
                .filter(m -> m.footer != null && m.footer.startsWith("blackhole-staff:")).count();
        assertEquals(1, controls, "one control message per ticket channel");
    }

    @Test
    void noControlMessageIsPostedWhileStaffActionsAreOff() {
        Harness off = new Harness();
        off.ready();
        FakeTicketBackend.Rec ticket = off.openTicketWithChannel(2);
        long controls = off.guild.messages(off.channelOf(ticket)).stream()
                .filter(m -> m.footer != null && m.footer.startsWith("blackhole-staff:")).count();
        assertEquals(0, controls, "the flag is off: the channel gets no control message");
    }

    @Test
    void caseEventForTheTicketsCaseRedrawsTheControlMessageOnce() {
        long before = edits();
        this.events.onMessage(caseEvent("case.status_changed", this.rec.caseReference));
        assertEquals(before + 1, edits(), "one redraw for the event");
    }

    @Test
    void caseEventForAnotherCaseDoesNothing() {
        long before = edits();
        this.events.onMessage(caseEvent("case.status_changed", "00000000-0000-4000-8000-000000000000"));
        assertEquals(before, edits(), "another case is not this ticket");
    }

    @Test
    void discordOutageDuringTheRedrawIsNotRetried() {
        this.h.guild.failOn.put("edit", new GatewayException("outage", false, null));
        long before = edits();
        this.events.onMessage(caseEvent("case.status_changed", this.rec.caseReference));
        assertEquals(before + 1, edits(), "one attempt, no retry loop");
    }
}
