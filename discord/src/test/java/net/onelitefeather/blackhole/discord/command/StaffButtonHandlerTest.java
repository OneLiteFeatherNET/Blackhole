package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffLink;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffOutcome;
import net.onelitefeather.blackhole.discord.sync.ControlMessageSync;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffButtonHandlerTest {

    static final String STAFF_OID = "7d1f3c2a-0000-4000-8000-00000000a001";

    /** Records what the bot would say to the presser. */
    static final class RecordingReply implements StaffButtonHandler.Reply {
        final List<String> texts = new ArrayList<>();
        final List<Action> modals = new ArrayList<>();

        @Override
        public void text(String text) {
            this.texts.add(text);
        }

        @Override
        public void decisionModal(Action decision, String ticketId) {
            this.modals.add(decision);
        }
    }

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

    private StaffButtonHandler.Press press(long user, Action action, String ticketId, boolean staffRole, RecordingReply reply) {
        return new StaffButtonHandler.Press(this.channel, user, StaffControlIds.id(action, ticketId), staffRole, reply);
    }

    @Test
    void unlinkedMemberIsToldToRunLinkAndNothingChanges() {
        this.h.backend.staffLinkAnswer = new StaffLink.NotLinked();
        RecordingReply reply = new RecordingReply();
        this.handler.onPress(press(Harness.STAFF_A, Action.CLAIM, this.rec.id, true, reply));
        assertTrue(reply.texts.stream().anyMatch(t -> t.contains("/link")), "the hint names /link: " + reply.texts);
        assertTrue(this.h.backend.staffWrites.isEmpty(), "no backend write for an unlinked member");
    }

    @Test
    void memberWithoutTheStaffRoleIsRefused() {
        RecordingReply reply = new RecordingReply();
        this.handler.onPress(press(Harness.PLAYER, Action.CLAIM, this.rec.id, false, reply));
        assertEquals(1, reply.texts.size(), "one refusal");
        assertTrue(this.h.backend.staffWrites.isEmpty(), "no backend write without the staff role");
    }

    @Test
    void buttonIdForAnotherTicketIsIgnored() {
        RecordingReply reply = new RecordingReply();
        String otherTicket = "c0ffee00-0000-4000-8000-0000000000ff";
        this.handler.onPress(press(Harness.STAFF_A, Action.CLAIM, otherTicket, true, reply));
        assertTrue(reply.texts.stream().anyMatch(t -> t.contains("out of date")), "the press is answered as out of date: " + reply.texts);
        assertTrue(this.h.backend.staffWrites.isEmpty(), "nothing written for another ticket");
    }

    @Test
    void claimIsRecordedUnderTheStaffOidWithTheVersionReadBefore() {
        RecordingReply reply = new RecordingReply();
        this.handler.onPress(press(Harness.STAFF_A, Action.CLAIM, this.rec.id, true, reply));
        assertTrue(this.h.backend.staffWrites.contains("claim:" + this.rec.caseReference + ":7:" + STAFF_OID),
                "claim writes the staff oid with the expected version: " + this.h.backend.staffWrites);
    }

    @Test
    void backendConflictIsShownAndTheControlMessageIsRedrawnFromTheCurrentState() {
        this.h.backend.claimAnswer = new StaffOutcome.Refused(409, "VERSION_CONFLICT", "Changed by someone else, press again");
        RecordingReply reply = new RecordingReply();
        int editsBefore = this.h.guild.calls.stream().filter("edit"::equals).toList().size();
        this.handler.onPress(press(Harness.STAFF_A, Action.CLAIM, this.rec.id, true, reply));
        assertTrue(reply.texts.contains("Changed by someone else, press again"), "the server text is shown: " + reply.texts);
        assertEquals(editsBefore + 1, this.h.guild.calls.stream().filter("edit"::equals).toList().size(),
                "the control message is redrawn once");
    }

    @Test
    void lockIsRecordedAsAStaffFreezeUnderTheStaffOid() {
        RecordingReply reply = new RecordingReply();
        this.handler.onPress(press(Harness.STAFF_A, Action.LOCK, this.rec.id, true, reply));
        assertTrue(this.h.backend.staffWrites.contains("freeze:" + this.rec.caseReference + ":true:" + STAFF_OID),
                "lock writes a freeze under the staff oid: " + this.h.backend.staffWrites);
        assertTrue(this.h.guild.texts(this.channel).stream().anyMatch(t -> t.contains("locked")), "the channel says it is locked");
    }

    @Test
    void acceptOpensTheDecisionModalWithoutAnyBackendCall() {
        RecordingReply reply = new RecordingReply();
        this.handler.onPress(press(Harness.STAFF_A, Action.ACCEPT, this.rec.id, true, reply));
        assertEquals(List.of(Action.ACCEPT), reply.modals);
        assertTrue(this.h.backend.staffWrites.isEmpty(), "the modal opens before any write");
    }

    @Test
    void closeBeforeTheDecisionIsRefusedAndNobodyIsRecorded() {
        RecordingReply reply = new RecordingReply();
        this.handler.onPress(press(Harness.STAFF_A, Action.CLOSE, this.rec.id, true, reply));
        assertTrue(reply.texts.stream().anyMatch(t -> t.contains("Decide")), "close is refused until the appeal is decided: " + reply.texts);
        assertTrue(this.h.backend.closedBy.isEmpty(), "no close is recorded");
    }
}
