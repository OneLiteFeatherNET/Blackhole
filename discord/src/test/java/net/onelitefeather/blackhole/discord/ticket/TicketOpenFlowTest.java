package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.Opened;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.command.Lang;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.testing.FakeGuild;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketOpenFlowTest {

    private static final String PLAYER = Long.toString(Harness.PLAYER);
    private static final String BAN_ID = "K7M2-9QXA";

    private static Result.Failed<Opened> refusal(int status, String code) {
        return Result.Failed.of(status, code);
    }

    @Test
    void opening_a_ticket_creates_a_private_channel_for_the_player_and_reports_it_to_the_backend() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(42);
        h.opens(ticket);

        String reply = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "please review");

        FakeGuild.Chan channel = h.guild.channelByTopic("blackhole-ticket:" + ticket.id).orElseThrow();
        assertEquals("appeal-0042", channel.name, "channel is named after the ticket number");
        assertEquals(Harness.TICKET_CATEGORY, channel.category, "created in the ticket category");
        assertEquals(State.OPEN, ticket.state, "the backend ticket is open once the channel is reported");
        assertEquals(Long.toString(channel.id), ticket.channelId, "the backend knows the channel");
        assertTrue(reply.contains("<#" + channel.id + ">"), "the reply points to the channel: " + reply);
    }

    @Test
    void the_player_may_write_but_not_attach_files_or_embed_links_and_the_bot_has_access_but_nobody_else() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(7);
        h.opens(ticket);

        h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        FakeGuild.Chan channel = h.guild.channelByTopic(ticket.id).orElseThrow();
        assertEquals(Set.of(Harness.PLAYER, FakeGuild.SELF), channel.members.keySet(), "only the player and the bot have an overwrite");
        var player = channel.members.get(Harness.PLAYER);
        assertEquals(Set.of(Permission.VIEW, Permission.SEND, Permission.READ_HISTORY), player.allow(), "player allow");
        assertEquals(Set.of(Permission.ATTACH_FILES, Permission.EMBED_LINKS), player.deny(), "player deny");
    }

    @Test
    void channel_name_and_topic_hold_no_ban_id() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(3);
        h.opens(ticket);

        h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        FakeGuild.Chan channel = h.guild.channelByTopic(ticket.id).orElseThrow();
        assertFalse((channel.name + channel.topic).toUpperCase().contains("K7M29QXA"), "no ban id in name or topic");
        assertFalse((channel.name + channel.topic).toUpperCase().contains("K7M2"), "no part of the ban id either");
    }

    @Test
    void the_reply_never_echoes_the_ban_id() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(3);
        h.opens(ticket);
        String opened = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");
        h.backend.openResult = refusal(404, "NOT_ELIGIBLE");
        String refused = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        assertFalse(opened.contains("K7M2") || refused.contains("K7M2"), "the ban id is not echoed");
    }

    @Test
    void every_refusal_gets_the_same_text() {
        Harness h = new Harness();
        h.backend.openResult = refusal(404, "NOT_ELIGIBLE");
        String wrong = h.appeals.open(Lang.EN, PLAYER, "Player", "AAAAAAAA", "statement");
        h.backend.openResult = refusal(404, null);
        String leakedOrBound = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");
        h.backend.openResult = refusal(400, null);
        String malformed = h.appeals.open(Lang.EN, PLAYER, "Player", "!!", "statement");

        assertEquals(wrong, leakedOrBound, "wrong, bound-to-other and leaked look the same");
        assertEquals(wrong, malformed, "a malformed id gets the same text");
    }

    @Test
    void a_missing_statement_is_asked_for() {
        Harness h = new Harness();
        h.backend.openResult = refusal(400, "STATEMENT_REQUIRED");

        String reply = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "  ");

        assertTrue(reply.toLowerCase().contains("statement"), reply);
        assertNotEquals(refusalText(h), reply, "different from the generic refusal");
    }

    private static String refusalText(Harness h) {
        h.backend.openResult = refusal(404, "NOT_ELIGIBLE");
        return h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "x");
    }

    @Test
    void a_cooldown_names_the_time_the_next_appeal_is_possible() {
        Harness h = new Harness();
        h.backend.openResult = new Result.Failed<>(409, "NOT_YET", 1_800_000_000_000L, null, null);

        String reply = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        assertTrue(reply.contains("<t:1800000000:F>"), "a Discord timestamp for the available time: " + reply);
    }

    @Test
    void a_blank_ban_id_is_not_sent_to_the_backend() {
        Harness h = new Harness();

        String reply = h.appeals.open(Lang.EN, PLAYER, "Player", "   ", "statement");

        assertFalse(h.backend.calls.contains("open"), "no backend call");
        assertTrue(reply.toLowerCase().contains("ban id"), reply);
    }

    @Test
    void backend_down_or_an_edge_429_say_try_again_later_and_create_nothing() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(1);
        h.opens(ticket);
        h.backend.down = 0;
        String down = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");
        h.backend.down = 429;
        String limited = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        assertTrue(down.contains("try again later"), down);
        assertEquals(down, limited, "429 is treated like an outage");
        assertTrue(h.guild.channels.isEmpty(), "no channel was made");
    }

    @Test
    void a_german_discord_client_gets_german_texts() {
        Harness h = new Harness();
        h.backend.openResult = refusal(404, "NOT_ELIGIBLE");

        String german = h.appeals.open(Lang.fromDiscordLocale("de"), PLAYER, "Player", BAN_ID, "x");

        assertTrue(german.contains("Einspruch"), german);
    }

    @Test
    void a_channel_that_could_not_be_created_is_finished_by_the_next_poll() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(5);
        h.opens(ticket);
        h.guild.failOn.put("createChannel", new GatewayException("down", false, null));

        String reply = h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        assertTrue(reply.contains("being prepared"), reply);
        assertEquals(State.PENDING_CHANNEL, ticket.state, "the ticket stays pending");

        h.guild.failOn.clear();
        h.ready();
        assertEquals(State.OPEN, ticket.state, "the next poll creates the channel");
        assertEquals(1, h.guild.channels.size(), "exactly one channel");
    }

    @Test
    void a_crash_between_the_channel_and_its_report_makes_no_second_channel_after_a_restart() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(9);
        h.opens(ticket);
        h.backend.failing.add("patch");
        h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");
        assertEquals(1, h.guild.channels.size(), "the channel exists");
        assertEquals(State.PENDING_CHANNEL, ticket.state, "but the backend was not told");

        h.backend.failing.clear();
        Harness restarted = new Harness(h);
        restarted.ready();

        assertEquals(1, restarted.guild.channels.size(), "still one channel: it was found by its topic");
        assertEquals(State.OPEN, ticket.state, "the restart reported it");
        assertNotNull(ticket.channelId);
    }

    @Test
    void earlier_portal_messages_are_posted_in_order_before_the_channel_is_reported() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.newTicket(11);
        h.opens(ticket);
        h.portalMessage(ticket, 1, AuthorKind.PLAYER, "first from the portal");
        h.portalMessage(ticket, 2, AuthorKind.STAFF, "second from staff");

        h.appeals.open(Lang.EN, PLAYER, "Player", BAN_ID, "statement");

        FakeGuild.Chan channel = h.guild.channelByTopic(ticket.id).orElseThrow();
        List<String> relayed = channel.messages.stream().filter(m -> m.label != null).map(m -> m.label + ": " + m.text).toList();
        assertEquals(List.of("Player (portal): first from the portal", "Moderation team: second from staff"), relayed, "in case order with labels");
        assertTrue(h.backend.calls.indexOf("link:2") < h.backend.calls.indexOf("patch:channel"), "relayed before the channel is reported: " + h.backend.calls);
    }

    @Test
    void the_appeal_button_message_is_posted_once() {
        Harness h = new Harness();
        h.guild.addChannel(Harness.APPEAL_CHANNEL, "appeals", 0);

        h.appeals.ensurePanel(h.guild, Harness.APPEAL_CHANNEL);
        h.appeals.ensurePanel(h.guild, Harness.APPEAL_CHANNEL);

        assertEquals(1, h.guild.panels.size(), "found again by its marker, not posted twice");
    }
}
