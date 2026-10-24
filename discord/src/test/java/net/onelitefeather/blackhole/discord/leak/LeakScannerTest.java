package net.onelitefeather.blackhole.discord.leak;

import net.onelitefeather.blackhole.discord.config.BotConfig.LeakMode;
import net.onelitefeather.blackhole.discord.config.BotConfig.LeakSettings;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import net.onelitefeather.blackhole.discord.testing.FakeGuild;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeakScannerTest {

    private static final long GENERAL = 500L;
    private static final long OTHER_BOT = 800L;
    private static final long MEMBER = 123456789012349001L;

    private static Harness publicWorld() {
        Harness h = new Harness();
        h.guild.addChannel(GENERAL, "general", 0);
        return h;
    }

    private static FakeTicketBackend.Rec withBanId(Harness h, String normalised) {
        FakeTicketBackend.Rec ticket = h.newTicket(1);
        h.backend.banIds.put(normalised, ticket);
        return ticket;
    }

    private static boolean visible(Harness h, long channel, long message) {
        return h.guild.messages(channel).stream().anyMatch(m -> m.id == message);
    }

    // ---- scope ----

    @Test
    void a_message_in_a_public_channel_is_scanned() {
        Harness h = publicWorld();

        h.guild.say(GENERAL, MEMBER, "check #K7M2-9QXA out");
        h.drain();

        assertEquals(List.of(List.of("K7M29QXA")), h.backend.leakChecks.stream().map(c -> c.candidates()).toList(), "the normalised candidate was sent");
        assertFalse(h.backend.leakChecks.getFirst().listed(), "as a public channel");
    }

    @Test
    void a_message_without_a_candidate_never_reaches_the_backend() {
        Harness h = publicWorld();

        h.guild.say(GENERAL, MEMBER, "hello ABCDEFGH 12345678 world");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "nothing sent");
    }

    @Test
    void a_channel_that_everyone_cannot_see_is_not_scanned() {
        Harness h = publicWorld();
        h.guild.addChannel(501L, "staff-only", 0).publicView = false;

        h.guild.say(501L, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "private channels are not scanned");
    }

    @Test
    void a_ban_id_written_in_a_ticket_channel_is_not_scanned_and_is_not_a_leak() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.backend.banIds.put("K7M29QXA", ticket);

        h.guild.say(h.channelOf(ticket), Harness.PLAYER, "my id is #K7M2-9QXA");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "ticket channels are private to the case");
        assertFalse(ticket.leaked, "and the ticket is not marked leaked");
    }

    @Test
    void the_archive_category_is_not_scanned_even_if_a_channel_there_looks_public() {
        Harness h = publicWorld();
        h.guild.addChannel(502L, "old-ticket", Harness.ARCHIVE_CATEGORY);

        h.guild.say(502L, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "excluded by category");
    }

    @Test
    void the_ticket_category_is_excluded_even_when_a_listed_channel_is_in_it() {
        Harness h = new Harness(new LeakSettings(LeakMode.LIST, Set.of(503L)));
        h.guild.addChannel(503L, "listed-but-in-tickets", Harness.TICKET_CATEGORY);

        h.guild.say(503L, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "the ticket category wins");
    }

    @Test
    void list_mode_scans_only_the_listed_channels_and_says_so() {
        Harness h = new Harness(new LeakSettings(LeakMode.LIST, Set.of(GENERAL)));
        h.guild.addChannel(GENERAL, "general", 0);
        h.guild.addChannel(504L, "unlisted", 0);

        h.guild.say(504L, MEMBER, "#K7M2-9QXA");
        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertEquals(1, h.backend.leakChecks.size(), "only the listed channel");
        assertTrue(h.backend.leakChecks.getFirst().listed(), "reported as a listed channel");
    }

    @Test
    void list_mode_accepts_a_category_id_for_everything_in_it() {
        Harness h = new Harness(new LeakSettings(LeakMode.LIST, Set.of(900L)));
        h.guild.addChannel(505L, "in-listed-category", 900L).publicView = false;

        h.guild.say(505L, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertEquals(1, h.backend.leakChecks.size(), "the category is on the list");
    }

    @Test
    void off_sends_no_content_to_the_backend() {
        Harness h = new Harness(new LeakSettings(LeakMode.OFF, Set.of()));
        h.guild.addChannel(GENERAL, "general", 0);

        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "scanning is off");
        assertFalse(h.backend.calls.contains("leak:failed"), "not even an attempt");
    }

    @Test
    void the_bots_own_messages_are_not_scanned() {
        Harness h = publicWorld();

        h.guild.say(GENERAL, FakeGuild.SELF, "#K7M2-9QXA");
        h.drain();

        assertTrue(h.backend.leakChecks.isEmpty(), "the bot does not scan itself");
    }

    @Test
    void an_edited_message_is_scanned_too() {
        Harness h = publicWorld();
        long message = h.guild.say(GENERAL, MEMBER, "harmless");
        h.drain();
        assertTrue(h.backend.leakChecks.isEmpty(), "precondition");

        h.guild.userEdits(GENERAL, message, "now with #K7M2-9QXA");
        h.drain();

        assertEquals(1, h.backend.leakChecks.size(), "the edit was checked");
    }

    @Test
    void history_is_not_scanned_on_start() {
        Harness h = publicWorld();
        h.guild.sayWhileBotDown(GENERAL, MEMBER, "#K7M2-9QXA written long ago");

        h.ready();
        h.ticks(2);

        assertTrue(h.backend.leakChecks.isEmpty(), "only new and edited messages are scanned");
    }

    @Test
    void a_human_poster_is_passed_by_id_and_a_bot_is_passed_as_a_bot_without_an_id() {
        Harness h = publicWorld();
        h.guild.bots.add(OTHER_BOT);

        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.guild.say(GENERAL, OTHER_BOT, "#ABCD-EFGH");
        h.drain();

        assertEquals(Long.toString(MEMBER), h.backend.leakChecks.get(0).poster(), "the human's id");
        assertFalse(h.backend.leakChecks.get(0).posterIsBot(), "not a bot");
        assertEquals(null, h.backend.leakChecks.get(1).poster(), "no id for a bot");
        assertTrue(h.backend.leakChecks.get(1).posterIsBot(), "flagged as a bot");
    }

    // ---- verification and action ----

    @Test
    void a_look_alike_the_backend_does_not_know_leaves_the_message_and_causes_nothing() {
        Harness h = publicWorld();

        long message = h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA is not a ban id");
        h.drain();

        assertTrue(visible(h, GENERAL, message), "the message stays");
        assertEquals(1, h.guild.messages(GENERAL).size(), "no notice either");
        assertEquals(0, h.metrics.value(Counter.LEAK_MESSAGES_REMOVED), "nothing removed");
    }

    @Test
    void a_real_ban_id_is_deleted_and_a_notice_without_any_identifier_is_posted() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = withBanId(h, "K7M29QXA");

        long message = h.guild.say(GENERAL, MEMBER, "here: #K7M2-9QXA");
        h.drain();

        assertFalse(visible(h, GENERAL, message), "the message is deleted");
        assertTrue(ticket.leaked, "the backend marked the case leaked");
        List<String> texts = h.guild.texts(GENERAL);
        assertEquals(1, texts.size(), "one notice is left");
        assertTrue(texts.getFirst().contains("ban code was removed"), texts.getFirst());
        assertFalse(texts.getFirst().toUpperCase().contains("K7M2") || texts.getFirst().contains(Long.toString(MEMBER)), "no identifier in the notice");
        assertEquals(1, h.metrics.value(Counter.LEAK_MESSAGES_REMOVED), "counted");
    }

    @Test
    void posting_an_already_leaked_ban_id_again_still_deletes_the_message_and_marks_nothing_new() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = withBanId(h, "K7M29QXA");
        ticket.leaked = true;

        long message = h.guild.say(GENERAL, MEMBER, "#K7M29QXA again");
        h.drain();

        assertFalse(visible(h, GENERAL, message), "deleted");
        assertEquals(1, h.backend.leakChecks.size(), "the backend was asked");
        assertTrue(ticket.leaked, "and it stays leaked");
    }

    @Test
    void a_retired_ban_id_that_the_backend_no_longer_knows_leaves_the_message() {
        Harness h = publicWorld();

        long message = h.guild.say(GENERAL, MEMBER, "#OLD0-ID00");
        h.drain();

        assertTrue(visible(h, GENERAL, message), "the backend says no match, so nothing happens");
    }

    @Test
    void a_missing_manage_messages_permission_is_counted_and_the_message_stays() {
        Harness h = publicWorld();
        withBanId(h, "K7M29QXA");
        h.guild.failOn.put("deleteMessage", new GatewayException("missing permissions", true, null));

        long message = h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();

        assertTrue(visible(h, GENERAL, message), "it could not be removed");
        assertEquals(1, h.metrics.value(Counter.DISCORD_ERRORS), "the failure is counted");
        assertEquals(0, h.metrics.value(Counter.LEAK_MESSAGES_REMOVED), "and not reported as removed");
    }

    // ---- backend down ----

    @Test
    void while_the_backend_is_down_the_candidate_is_retried_and_acted_on_once_it_is_back() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = withBanId(h, "K7M29QXA");
        h.backend.down = 0;

        long message = h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();
        h.scheduler.advance(Duration.ofMinutes(3));
        assertTrue(visible(h, GENERAL, message), "still there while nothing could be verified");
        assertEquals(1, h.leakScanner.pending(), "waiting in the bounded queue");

        h.backend.down = null;
        h.scheduler.advance(Duration.ofMinutes(1));

        assertFalse(visible(h, GENERAL, message), "removed after the backend came back");
        assertTrue(ticket.leaked, "and marked");
        assertEquals(0, h.leakScanner.pending(), "queue empty");
    }

    @Test
    void after_five_minutes_without_a_backend_the_candidate_is_dropped_with_a_counter() {
        Harness h = publicWorld();
        withBanId(h, "K7M29QXA");
        h.backend.down = 0;

        long message = h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();
        h.scheduler.advance(Duration.ofMinutes(10));

        assertTrue(visible(h, GENERAL, message), "never verified, so never acted on");
        assertEquals(1, h.metrics.value(Counter.LEAK_CANDIDATES_DROPPED), "dropped once, counted");
        assertEquals(0, h.leakScanner.pending(), "no longer queued");
        assertTrue(h.backend.leakChecks.isEmpty(), "the backend never saw it");
    }

    @Test
    void retries_back_off_instead_of_hammering_the_backend() {
        Harness h = publicWorld();
        h.backend.down = 503;

        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();
        h.scheduler.advance(Duration.ofMinutes(5));

        long attempts = h.backend.calls.stream().filter("leak:failed"::equals).count();
        assertTrue(attempts >= 4 && attempts <= 10, "a handful of attempts in five minutes, not one per tick: " + attempts);
    }

    @Test
    void a_429_from_the_edge_is_treated_like_an_outage() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = withBanId(h, "K7M29QXA");
        h.backend.failNext = 2;
        h.backend.failNextStatus = 429;

        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();
        h.scheduler.advance(Duration.ofMinutes(1));

        assertTrue(ticket.leaked, "retried after the limiter let it through");
    }

    @Test
    void a_refusal_that_is_not_an_outage_drops_the_candidate_without_retrying() {
        Harness h = publicWorld();
        h.backend.leakStatus = 400;

        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();
        h.scheduler.advance(Duration.ofMinutes(1));

        assertEquals(1, h.backend.calls.stream().filter("leak:failed"::equals).count(), "asked once, not retried");
        assertEquals(0, h.leakScanner.pending(), "not kept");
        assertEquals(1, h.metrics.value(Counter.LEAK_CANDIDATES_DROPPED), "counted as dropped");
    }

    @Test
    void the_queue_is_bounded_and_overflow_is_dropped_with_a_counter() {
        Harness h = publicWorld();
        h.backend.down = 0;

        for (int i = 0; i < LeakScanner.MAX_PENDING + 25; i++) {
            h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        }
        h.drain();

        assertEquals(LeakScanner.MAX_PENDING, h.leakScanner.pending(), "bounded");
        assertEquals(25, h.metrics.value(Counter.LEAK_CANDIDATES_DROPPED), "the overflow is counted");
    }

    // ---- the freeze follows from the leak ----

    @Test
    void a_leak_during_an_open_ticket_freezes_the_ticket_channel_end_to_end() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.backend.banIds.put("K7M29QXA", ticket);

        long message = h.guild.say(GENERAL, MEMBER, "found this: #K7M2-9QXA");
        h.drain();
        assertFalse(visible(h, GENERAL, message), "removed from the public channel");
        assertTrue(ticket.leaked, "the backend marked the case");
        h.caseEvents.onMessage("{\"eventId\":\"e\",\"eventType\":\"case.ban_id_leak_marked\",\"payload\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        h.drain();

        assertTrue(ticket.frozen, "the hint made the bot poll and freeze");
        assertTrue(h.guild.channel(h.channelOf(ticket)).members.get(Harness.PLAYER).deny().contains(
                net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission.SEND), "the player can no longer write");
    }

    @Test
    void the_freeze_also_follows_without_any_broker_event() {
        Harness h = publicWorld();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.backend.banIds.put("K7M29QXA", ticket);

        h.guild.say(GENERAL, MEMBER, "#K7M2-9QXA");
        h.drain();
        h.tick();

        assertTrue(ticket.frozen, "the cursor poll is enough");
    }
}
