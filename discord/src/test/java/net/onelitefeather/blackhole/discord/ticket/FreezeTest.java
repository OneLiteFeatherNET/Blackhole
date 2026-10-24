package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MemberAccess;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import net.onelitefeather.blackhole.discord.testing.FakeGuild;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeTest {

    private static final byte[] LEAK_EVENT = "{\"eventId\":\"e1\",\"eventType\":\"case.ban_id_leak_marked\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REISSUE_EVENT = "{\"eventId\":\"e2\",\"eventType\":\"case.ban_id_reissued\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);

    private static MemberAccess player(Harness h, FakeTicketBackend.Rec ticket) {
        return h.guild.channel(h.channelOf(ticket)).members.get(Harness.PLAYER);
    }

    private static long notices(Harness h, FakeTicketBackend.Rec ticket, String start) {
        return h.guild.texts(h.channelOf(ticket)).stream().filter(t -> t.startsWith(start)).count();
    }

    @Test
    void a_leak_event_freezes_the_channel_for_the_player_and_posts_a_notice() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;

        assertEquals(Outcome.DONE, h.caseEvents.onMessage(LEAK_EVENT), "the event is a hint");
        h.drain();

        assertTrue(player(h, ticket).deny().contains(Permission.SEND), "the player can no longer write");
        assertTrue(player(h, ticket).allow().contains(Permission.VIEW), "but can still read");
        assertTrue(ticket.frozen, "the backend mirror says frozen");
        assertEquals(1, notices(h, ticket, "This channel is frozen"), "one notice");
        assertEquals(1, h.metrics.value(Counter.FREEZES), "counted");
    }

    @Test
    void the_team_can_still_write_in_a_frozen_channel() {
        Harness h = new Harness();
        h.staffRoleFor(Harness.STAFF_A);
        h.backend.staffLinks.add(Long.toString(Harness.STAFF_A));
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;

        h.tick();

        assertTrue(h.guild.channel(h.channelOf(ticket)).members.get(Harness.STAFF_A).allow().contains(Permission.SEND), "staff keep Send");
    }

    @Test
    void freezing_is_applied_once_not_on_every_poll() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;

        h.ticks(4);

        assertEquals(1, notices(h, ticket, "This channel is frozen"), "one notice only");
        assertEquals(1, h.metrics.value(Counter.FREEZES), "one freeze");
    }

    @Test
    void a_reissued_ban_id_thaws_the_channel() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;
        h.tick();
        assertTrue(ticket.frozen, "precondition");

        ticket.leaked = false;
        h.caseEvents.onMessage(REISSUE_EVENT);
        h.drain();

        assertFalse(ticket.frozen, "mirror cleared");
        assertTrue(player(h, ticket).allow().contains(Permission.SEND), "the player can write again");
        assertEquals(1, notices(h, ticket, "This channel is open again"), "a notice");
    }

    @Test
    void a_freeze_survives_a_restart_without_a_second_notice() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;
        h.tick();

        Harness restarted = new Harness(h);
        restarted.ready();
        restarted.tick();

        assertEquals(1, notices(h, ticket, "This channel is frozen"), "still one notice");
        assertTrue(player(h, ticket).deny().contains(Permission.SEND), "still frozen");
    }

    @Test
    void a_failed_discord_call_leaves_the_mirror_unset_so_the_next_poll_retries() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;
        h.guild.failOn.put("setMemberAccess", new net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException("x", false, null));

        h.tick();
        assertFalse(ticket.frozen, "not claimed to be frozen");

        h.guild.failOn.clear();
        h.tick();

        assertTrue(ticket.frozen, "frozen on the retry");
    }

    @Test
    void a_closed_ticket_is_not_unfrozen_by_a_reissue() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.leaked = true;
        h.tick();
        ticket.appealStatus = "DENIED";
        h.tick();
        assertEquals(State.ARCHIVED, ticket.state, "precondition");

        ticket.leaked = false;
        h.tick();

        FakeGuild.Chan channel = h.guild.channel(h.channelOf(ticket));
        assertTrue(channel.members.get(Harness.PLAYER).deny().contains(Permission.SEND), "a decided ticket stays locked");
    }
}
