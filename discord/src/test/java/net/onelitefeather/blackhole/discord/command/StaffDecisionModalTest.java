package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffOutcome;
import net.onelitefeather.blackhole.discord.sync.ControlMessageSync;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffDecisionModalTest {

    private Harness h;
    private FakeTicketBackend.Rec rec;
    private long channel;
    private StaffButtonHandler handler;

    @BeforeEach
    void setUp() {
        this.h = new Harness();
        ControlMessageSync sync = this.h.enableStaffActions();
        this.h.ready();
        this.rec = this.h.openTicketWithChannel(1);
        this.channel = this.h.channelOf(this.rec);
        this.handler = this.h.staffButtons(sync);
        this.h.staffRoleFor(Harness.STAFF_A);
    }

    private StaffButtonHandler.Submit submit(Action decision, String message, StaffButtonHandler.Reply reply) {
        return new StaffButtonHandler.Submit(this.channel, Harness.STAFF_A, decision, this.rec.id, message, true, reply);
    }

    @Test
    void rejectWithAnEmptyMessageIsNotSent() {
        StaffButtonHandlerTest.RecordingReply reply = new StaffButtonHandlerTest.RecordingReply();
        this.handler.onDecision(submit(Action.REJECT, "   ", reply));
        assertTrue(reply.texts.stream().anyMatch(t -> t.contains("required")), "the modal asks for the player message: " + reply.texts);
        assertFalse(this.h.backend.staffWrites.stream().anyMatch(w -> w.startsWith("review:")), "no review is sent");
    }

    @Test
    void rejectWithAMessageIsSentUnderTheStaffOid() {
        StaffButtonHandlerTest.RecordingReply reply = new StaffButtonHandlerTest.RecordingReply();
        this.handler.onDecision(submit(Action.REJECT, "Your appeal does not show a change.", reply));
        assertTrue(this.h.backend.staffWrites.contains("review:" + this.rec.appealId + ":REJECT:Your appeal does not show a change.:"
                + StaffButtonHandlerTest.STAFF_OID), "the review carries the staff oid: " + this.h.backend.staffWrites);
    }

    @Test
    void serverRefusalTextIsShownEphemerally() {
        this.h.backend.reviewAnswer = new StaffOutcome.Refused(422, "PII_IN_MESSAGE", "The message holds a UUID, remove it.");
        StaffButtonHandlerTest.RecordingReply reply = new StaffButtonHandlerTest.RecordingReply();
        this.handler.onDecision(submit(Action.ACCEPT, "Thanks for the statement.", reply));
        assertTrue(reply.texts.contains("The message holds a UUID, remove it."), "the 422 text is shown: " + reply.texts);
    }

    @Test
    void acceptWithoutAMessageIsSent() {
        StaffButtonHandlerTest.RecordingReply reply = new StaffButtonHandlerTest.RecordingReply();
        this.handler.onDecision(submit(Action.ACCEPT, "", reply));
        assertTrue(this.h.backend.staffWrites.stream().anyMatch(w -> w.startsWith("review:") && w.contains(":ACCEPT:")),
                "an accept needs no message: " + this.h.backend.staffWrites);
    }
}
