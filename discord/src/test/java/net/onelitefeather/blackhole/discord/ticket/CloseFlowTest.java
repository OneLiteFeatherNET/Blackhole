package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.testing.FakeGuild;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseFlowTest {

    /** An open ticket with two player messages and a decision from the panel; the appeal is decided. */
    private static FakeTicketBackend.Rec decided(Harness h) {
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        long channel = h.channelOf(ticket);
        h.guild.say(channel, Harness.PLAYER, "my side");
        h.guild.say(channel, Harness.PLAYER, "more detail");
        h.drain();
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "Your appeal is denied.");
        ticket.appealStatus = "DENIED";
        return ticket;
    }

    private static FakeTicketBackend.Rec archived(Harness h) {
        FakeTicketBackend.Rec ticket = decided(h);
        h.tick();
        assertEquals(State.ARCHIVED, ticket.state, "precondition: archived");
        return ticket;
    }

    @Test
    void a_decided_appeal_relays_the_decision_locks_the_player_closes_with_a_proof_and_archives() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);

        h.tick();

        FakeGuild.Chan channel = h.guild.channel(h.channelOf(ticket));
        assertEquals(State.ARCHIVED, ticket.state, "archived");
        assertEquals(Harness.ARCHIVE_CATEGORY, channel.category, "moved to the archive category");
        assertTrue(ticket.complete, "the completeness proof was accepted");
        assertEquals(2, ticket.count, "two player messages proven");
        assertNotNull(ticket.protocolVersion, "a protocol version exists");
        assertTrue(channel.members.get(Harness.PLAYER).deny().contains(Permission.SEND), "the player can no longer write");
        assertTrue(h.guild.texts(channel.id).stream().anyMatch(t -> t.equals("Your appeal is denied.")), "the decision was relayed first");
        assertTrue(h.backend.calls.indexOf("link:1") < h.backend.calls.indexOf("patch:CLOSING"), "relay before the close: " + h.backend.calls);
        assertTrue(h.backend.calls.indexOf("close") < h.backend.calls.indexOf("patch:ARCHIVED"), "close before archive: " + h.backend.calls);
    }

    @Test
    void an_appeal_still_under_review_keeps_the_channel_open() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.appealStatus = "IN_REVIEW";

        h.ticks(3);

        assertEquals(State.OPEN, ticket.state, "still open");
        assertFalse(h.backend.calls.contains("close"), "no close attempt");
    }

    @Test
    void a_channel_is_not_archived_while_no_protocol_version_exists() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);
        h.backend.undecided = true;

        h.ticks(2);

        assertEquals(State.CLOSED, ticket.state, "closed but not archived");
        assertEquals(Harness.TICKET_CATEGORY, h.guild.channel(h.channelOf(ticket)).category, "still in the ticket category");
        assertFalse(h.backend.calls.contains("patch:ARCHIVED"), "archive was not even attempted");

        h.backend.undecided = false;
        h.tick();

        assertEquals(State.ARCHIVED, ticket.state, "archived once a version exists");
    }

    @Test
    void a_protocol_that_cannot_be_stored_blocks_the_archive_and_is_retried() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);
        h.backend.protocolStoreFails = true;

        h.ticks(2);
        assertEquals(State.CLOSED, ticket.state, "closed, not archived");
        assertTrue(h.metrics.value(Counter.BACKEND_UNAVAILABLE) > 0, "counted");

        h.backend.protocolStoreFails = false;
        h.tick();

        assertEquals(State.ARCHIVED, ticket.state, "archived after the protocol could be stored");
    }

    @Test
    void a_transcript_mismatch_is_rescanned_and_the_retry_succeeds() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);
        h.backend.mismatchesToForce = 2;

        h.tick();

        assertEquals(3, h.backend.calls.stream().filter("close"::equals).count(), "closed on the third attempt");
        assertEquals(State.ARCHIVED, ticket.state, "archived");
        assertEquals(0, h.metrics.value(Counter.INCOMPLETE_TRANSCRIPTS), "not incomplete");
    }

    @Test
    void after_three_mismatches_the_ticket_is_closed_without_proof_and_the_channel_is_kept() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);
        h.backend.mismatchesToForce = 100;

        h.tick();

        assertEquals(3, h.backend.calls.stream().filter("close"::equals).count(), "exactly three attempts");
        assertEquals(State.CLOSED, ticket.state, "marked closed");
        assertFalse(ticket.complete, "without the completeness event");
        assertEquals(Harness.TICKET_CATEGORY, h.guild.channel(h.channelOf(ticket)).category, "channel not archived");
        assertEquals(1, h.metrics.value(Counter.INCOMPLETE_TRANSCRIPTS), "the metric is raised");

        h.clock.advance(Duration.ofDays(400));
        assertEquals(0, h.sweeper.sweep(), "an incomplete transcript is never deleted");
        assertTrue(h.guild.channels.containsKey(h.channelOf(ticket)), "the channel is still there");
    }

    @Test
    void the_close_rescan_records_an_edit_and_a_delete_made_while_the_bot_was_down() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        long channel = h.channelOf(ticket);
        long edited = h.guild.say(channel, Harness.PLAYER, "original text");
        long removed = h.guild.say(channel, Harness.PLAYER, "will vanish");
        h.guild.say(channel, Harness.PLAYER, "stays");
        h.drain();
        h.guild.userEditsWhileBotDown(channel, edited, "changed while down");
        h.guild.userDeletesWhileBotDown(channel, removed);
        ticket.appealStatus = "GRANTED_FULL_LIFT";

        h.tick();

        assertEquals("changed while down", ticket.inbound.get(edited), "the edit was found");
        assertTrue(ticket.deleted.contains(removed), "the delete was found");
        assertEquals(State.ARCHIVED, ticket.state, "and the proof was accepted");
        assertEquals(2, ticket.count, "two messages remain");
    }

    @Test
    void a_crash_during_the_close_resumes_where_it_stopped() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);
        h.backend.failing.add("close");
        h.tick();
        assertEquals(State.CLOSING, ticket.state, "stuck closing while the backend refuses");

        h.backend.failing.clear();
        Harness restarted = new Harness(h);
        restarted.ready();

        assertEquals(State.ARCHIVED, ticket.state, "the restart finished the close");
    }

    @Test
    void an_archived_channel_is_not_deleted_before_the_delay() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = archived(h);

        h.clock.advance(Duration.ofDays(29));

        assertEquals(0, h.sweeper.sweep(), "nothing is due");
        assertTrue(h.guild.channels.containsKey(h.channelOf(ticket)), "channel kept");
    }

    @Test
    void after_the_delay_the_protocol_is_ensured_first_and_then_the_channel_is_deleted() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = archived(h);
        long channel = h.channelOf(ticket);
        int ensuredBefore = h.backend.ensureCalls;
        h.clock.advance(Duration.ofDays(31));

        int deleted = h.sweeper.sweep();
        h.drain();

        assertEquals(1, deleted, "deleted");
        assertFalse(h.guild.channels.containsKey(channel), "channel gone");
        assertEquals(State.DELETED, ticket.state, "recorded");
        assertEquals(ensuredBefore + 1, h.backend.ensureCalls, "protocol/ensure was called before the delete");
        assertTrue(h.backend.calls.lastIndexOf("ensure") < h.backend.calls.lastIndexOf("patch:DELETED"), "ensure before DELETED: " + h.backend.calls);
        assertEquals(0, h.backend.calls.stream().filter("patch:gone"::equals).count(), "the bot's own delete is not reported as a gap");
    }

    @Test
    void a_late_edit_makes_the_delete_wait_for_a_fresh_protocol_version() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = archived(h);
        int generation = h.backend.protocolGeneration;
        h.backend.staleProtocol();
        h.clock.advance(Duration.ofDays(31));

        h.sweeper.sweep();
        h.drain();

        assertEquals(generation + 1, h.backend.protocolGeneration, "a new protocol version was made first");
        assertEquals(State.DELETED, ticket.state, "then the delete was accepted");
    }

    @Test
    void if_the_protocol_cannot_be_ensured_the_channel_is_not_deleted() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = archived(h);
        h.backend.protocolStoreFails = true;
        h.clock.advance(Duration.ofDays(31));

        assertEquals(0, h.sweeper.sweep(), "nothing deleted");
        assertTrue(h.guild.channels.containsKey(h.channelOf(ticket)), "channel kept");
        assertEquals(State.ARCHIVED, ticket.state, "still archived");
    }

    @Test
    void a_delay_of_zero_keeps_archived_channels_for_ever() {
        Harness first = new Harness();
        Harness h = new Harness(first.clock, first.backend, first.guild, Duration.ZERO);
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.appealStatus = "DENIED";
        h.tick();
        assertEquals(State.ARCHIVED, ticket.state, "precondition");

        h.clock.advance(Duration.ofDays(3650));

        assertEquals(0, h.sweeper.sweep(), "never deleted");
    }

    @Test
    void an_archived_channel_deleted_by_hand_is_recorded_after_the_protocol_is_made_current() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = archived(h);
        h.guild.userDeletesChannel(h.channelOf(ticket));
        h.drain();
        assertEquals(State.ARCHIVED, ticket.state, "not reported as a gap: the protocol holds the record");

        h.sweeper.sweep();

        assertEquals(State.DELETED, ticket.state, "recorded as deleted once ensure confirmed the protocol");
    }

    @Test
    void a_late_edit_in_the_archive_is_still_recorded() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        long message = h.guild.say(h.channelOf(ticket), Harness.PLAYER, "before");
        h.drain();
        ticket.appealStatus = "DENIED";
        h.tick();
        assertEquals(State.ARCHIVED, ticket.state, "precondition");

        h.guild.userEdits(h.channelOf(ticket), message, "after");
        h.drain();

        assertEquals("after", ticket.inbound.get(message), "recorded");
    }

    @Test
    void a_missing_permission_while_moving_the_channel_keeps_it_closed_and_is_retried() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = decided(h);
        h.guild.failOn.put("moveChannel", new GatewayException("missing permissions", true, null));

        h.ticks(2);
        assertEquals(State.CLOSED, ticket.state, "not archived while the move fails");
        assertTrue(h.metrics.value(Counter.DISCORD_ERRORS) > 0, "counted");

        h.guild.failOn.clear();
        h.tick();

        assertEquals(State.ARCHIVED, ticket.state, "archived after the permission was fixed");
    }
}
