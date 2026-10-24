package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncEngineTest {

    private static List<String> relayed(Harness h, FakeTicketBackend.Rec rec) {
        return h.guild.messages(h.channelOf(rec)).stream().filter(m -> m.label != null).map(m -> m.label + ": " + m.text).toList();
    }

    @Test
    void portal_and_panel_messages_are_relayed_in_case_order_and_the_cursor_advances() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 10, AuthorKind.STAFF, "one");
        h.portalMessage(ticket, 11, AuthorKind.PLAYER, "two");
        h.portalMessage(ticket, 12, AuthorKind.STAFF, "three");

        h.tick();

        assertEquals(List.of("Moderation team: one", "Player (portal): two", "Moderation team: three"), relayed(h, ticket), "order and labels");
        assertEquals(12, ticket.relayedSeq, "the relay cursor is at the last event");
    }

    @Test
    void a_second_poll_posts_nothing_again() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "one");
        h.tick();

        h.tick();
        h.tick();

        assertEquals(1, relayed(h, ticket).size(), "posted once");
    }

    @Test
    void a_restart_between_posting_and_acknowledging_does_not_post_the_message_twice() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "one");
        h.backend.failing.add("link");
        h.tick();
        assertEquals(1, relayed(h, ticket).size(), "posted");
        assertEquals(0, ticket.relayedSeq, "but not acknowledged");

        h.backend.failing.clear();
        Harness restarted = new Harness(h);
        restarted.ready();
        restarted.tick();

        assertEquals(1, relayed(restarted, ticket).size(), "the restart found its own message by the reference and linked it");
        assertEquals(1, ticket.relayedSeq, "acknowledged now");
    }

    @Test
    void a_message_written_in_discord_is_recorded_once() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        long id = h.guild.say(h.channelOf(ticket), Harness.PLAYER, "hello team");
        h.drain();
        h.ready();

        assertEquals("hello team", ticket.inbound.get(id), "recorded");
        assertEquals(1, ticket.inbound.size(), "once, even after a catch-up read the history again");
    }

    @Test
    void the_bots_own_messages_are_never_ingested() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "from the panel");
        h.tick();
        h.guild.say(h.channelOf(ticket), Harness.PLAYER, "reply");
        h.drain();

        h.ready();

        assertEquals(List.of("reply"), List.copyOf(ticket.inbound.values()), "only the human message is in the transcript");
    }

    @Test
    void an_edit_and_a_delete_in_discord_are_mirrored_to_the_backend() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        long channel = h.channelOf(ticket);
        long edited = h.guild.say(channel, Harness.PLAYER, "first version");
        long removed = h.guild.say(channel, Harness.PLAYER, "to be removed");
        h.drain();

        h.guild.userEdits(channel, edited, "second version");
        h.guild.userDeletes(channel, removed);
        h.drain();

        assertEquals("second version", ticket.inbound.get(edited), "edit recorded");
        assertEquals(1, ticket.edits.get(edited), "as one edit");
        assertTrue(ticket.deleted.contains(removed), "delete recorded");
    }

    @Test
    void an_edit_and_a_delete_from_the_panel_change_the_discord_copy() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        OutboxItem added = h.portalMessage(ticket, 1, AuthorKind.STAFF, "original");
        h.tick();
        String discordId = ticket.relayed.get(added.messageId());

        ticket.outbox.add(new OutboxItem(2, OutboxKind.EDIT, added.messageId(), null, "corrected", discordId));
        h.tick();
        assertEquals(List.of("Moderation team: corrected"), relayed(h, ticket), "edited in place");

        ticket.outbox.add(new OutboxItem(3, OutboxKind.DELETE, added.messageId(), null, null, discordId));
        h.tick();
        assertEquals(List.of(), relayed(h, ticket), "removed from the channel");
        assertEquals(3, ticket.relayedSeq, "all acknowledged");
    }

    @Test
    void a_broker_hint_polls_at_once_and_many_hints_make_one_poll() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "now");
        h.backend.calls.clear();
        byte[] event = "{\"eventId\":\"e1\",\"eventType\":\"case.message_added\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < 5; i++) {
            assertEquals(Outcome.DONE, h.caseEvents.onMessage(event), "handled");
        }
        h.drain();

        assertEquals(List.of("Moderation team: now"), relayed(h, ticket), "relayed without waiting for the interval");
        assertEquals(1, h.backend.calls.stream().filter("tickets"::equals).count(), "five hints, one poll");
    }

    @Test
    void without_the_broker_the_cursor_poll_alone_keeps_the_channel_in_sync() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "no broker needed");

        h.tick();

        assertEquals(List.of("Moderation team: no broker needed"), relayed(h, ticket), "relayed by the timer");
        assertEquals(0, h.metrics.value(Counter.BROKER_HINTS), "no hint was involved");
    }

    @Test
    void a_message_that_is_not_an_event_or_not_a_case_event_is_dropped() {
        Harness h = new Harness();

        assertEquals(Outcome.DROP, h.caseEvents.onMessage("not json".getBytes(StandardCharsets.UTF_8)), "garbage");
        assertEquals(Outcome.DROP, h.caseEvents.onMessage("{\"eventType\":\"report.created\"}".getBytes(StandardCharsets.UTF_8)), "other event");
    }

    @Test
    void a_backend_outage_loses_nothing_and_the_catch_up_records_it_afterwards() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        long channel = h.channelOf(ticket);
        long early = h.guild.say(channel, Harness.PLAYER, "before the outage");
        h.drain();

        h.backend.down = 0;
        long during = h.guild.say(channel, Harness.PLAYER, "during the outage");
        h.guild.userEdits(channel, early, "edited during the outage");
        h.drain();
        h.ticks(3);
        assertTrue(h.metrics.value(Counter.BACKEND_UNAVAILABLE) > 0, "the outage is counted");
        assertFalse(ticket.inbound.containsKey(during), "not recorded yet");

        h.backend.down = null;
        h.tick();

        assertEquals("during the outage", ticket.inbound.get(during), "the new message was read from Discord's history");
        assertEquals("edited during the outage", ticket.inbound.get(early), "the queued edit was sent");
    }

    @Test
    void a_401_from_an_expired_token_is_retried_by_the_next_poll() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "after the refresh");
        h.backend.failNext = 1;
        h.backend.failNextStatus = 401;

        h.tick();
        assertEquals(List.of(), relayed(h, ticket), "the first poll could not list tickets");
        h.tick();

        assertEquals(List.of("Moderation team: after the refresh"), relayed(h, ticket), "the second poll worked");
    }

    @Test
    void startup_catch_up_records_what_was_written_while_the_bot_was_down_and_relays_what_waited() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        long channel = h.channelOf(ticket);
        long a = h.guild.sayWhileBotDown(channel, Harness.PLAYER, "one");
        long b = h.guild.sayWhileBotDown(channel, Harness.PLAYER, "two");
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "waiting in the backend");

        Harness restarted = new Harness(h);
        restarted.ready();

        assertEquals("one", ticket.inbound.get(a), "first recorded");
        assertEquals("two", ticket.inbound.get(b), "second recorded");
        assertEquals(List.of("Moderation team: waiting in the backend"), relayed(restarted, ticket), "outbox relayed");
        assertTrue(restarted.metrics.value(Counter.CATCH_UPS) > 0, "counted");
    }

    @Test
    void a_discord_error_while_relaying_is_retried_by_a_later_poll() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.portalMessage(ticket, 1, AuthorKind.STAFF, "eventually");
        h.guild.failOn.put("postRelay", new GatewayException("rate limited", false, null));

        h.tick();
        assertEquals(0, ticket.relayedSeq, "not acknowledged while Discord failed");
        assertTrue(h.metrics.value(Counter.DISCORD_ERRORS) > 0, "counted");

        h.guild.failOn.clear();
        h.tick();

        assertEquals(List.of("Moderation team: eventually"), relayed(h, ticket), "posted once Discord answered");
    }

    @Test
    void a_ticket_channel_deleted_by_hand_is_reported_as_gone() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        h.guild.userDeletesChannel(h.channelOf(ticket));
        h.drain();

        assertEquals(State.DELETED, ticket.state, "the backend recorded the gap");
    }

    @Test
    void a_message_outside_the_ticket_channels_is_handed_to_the_other_sinks_and_not_ingested() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.guild.addChannel(500, "general", 0);

        h.guild.say(500, Harness.PLAYER, "hello everyone");
        h.drain();

        assertEquals(1, h.outsideMessages.size(), "given to the scanner sink");
        assertTrue(ticket.inbound.isEmpty(), "never recorded as a ticket message");
    }

    @Test
    void a_message_staff_wrote_in_the_channel_is_part_of_the_record() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        long id = h.guild.say(h.channelOf(ticket), Harness.STAFF_A, "we are looking into it");
        h.drain();

        assertEquals("we are looking into it", ticket.inbound.get(id), "staff messages in Discord are part of the record");
    }
}
