package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxFile;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxKind;
import net.onelitefeather.blackhole.discord.testing.FakeGuild;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The case to Discord direction of the appeal chat: Markdown, files, shares and their revoke. */
class OutboundChatTest {

    private static final String ZWSP = "​";

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static OutboxItem add(long seq, String text, OutboxFile... files) {
        return new OutboxItem(seq, OutboxKind.ADD, id(), AuthorKind.STAFF, text, null, List.of(files));
    }

    private static List<String> relayedTexts(Harness h, FakeTicketBackend.Rec rec) {
        return h.guild.messages(h.channelOf(rec)).stream().filter(m -> m.label != null).map(m -> m.text).toList();
    }

    @Test
    void markdown_is_written_with_discord_marks_and_a_link_without_an_embed() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.outbox.add(add(1, "**ok** see [docs](https://example.org)"));

        h.tick();

        assertEquals(List.of("**ok** see docs <https://example.org/>"), relayedTexts(h, ticket), "bold kept, link as text <url>");
    }

    @Test
    void mentions_in_literal_text_are_broken_up() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.outbox.add(add(1, "@everyone <@123456789012345678> <@&5>"));

        h.tick();

        String text = relayedTexts(h, ticket).getFirst();
        assertFalse(text.contains("@everyone"), "no ping: " + text);
        assertFalse(java.util.regex.Pattern.compile("(?<!\\\\)<@").matcher(text).find(), "no unescaped mention form: " + text);
        assertTrue(text.startsWith("@" + ZWSP + "everyone"), "a zero-width space after the at sign: " + text);
    }

    @Test
    void a_long_message_is_posted_in_parts_of_at_most_2000_characters() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.outbox.add(add(1, ("word ".repeat(150).trim() + "\n\n").repeat(6)));

        h.tick();

        List<String> parts = relayedTexts(h, ticket);
        assertTrue(parts.size() > 1, "several posts: " + parts.size());
        for (String part : parts) {
            assertTrue(part.length() <= 2000, "a post is " + part.length() + " characters");
        }
        assertEquals(1, ticket.relayedSeq, "acknowledged once for the whole message");
        assertEquals(1, ticket.relayed.size(), "linked to the first post");
    }

    @Test
    void a_message_with_a_pdf_uploads_the_fetched_bytes_to_the_channel_and_is_linked() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String attachment = id();
        byte[] pdf = {'%', 'P', 'D', 'F'};
        h.backend.files.put(attachment, pdf);
        OutboxItem item = add(1, "see the file", new OutboxFile(attachment, "report.pdf", "application/pdf", 4L));
        ticket.outbox.add(item);

        h.tick();

        assertEquals(1, h.guild.uploads.size(), "one upload");
        FakeGuild.Upload upload = h.guild.uploads.getFirst();
        assertEquals("report.pdf", upload.name(), "named as in the case");
        assertArrayEquals(pdf, upload.data(), "the bytes the backend served");
        assertEquals(h.channelOf(ticket), upload.channelId(), "in the ticket channel");
        assertEquals(List.of("see the file"), relayedTexts(h, ticket), "the text is posted too");
        assertTrue(ticket.relayed.containsKey(item.messageId()), "the message is linked");
        assertEquals(1, ticket.relayedSeq, "acknowledged");
    }

    @Test
    void an_image_goes_up_as_a_file_too() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String attachment = id();
        h.backend.files.put(attachment, new byte[]{1, 2});
        ticket.outbox.add(add(1, "", new OutboxFile(attachment, "shot.webp", "image/webp", 2L)));

        h.tick();

        assertEquals("shot.webp", h.guild.uploads.getFirst().name(), "uploaded as a file");
    }

    @Test
    void a_file_the_backend_says_is_not_visible_is_replaced_by_a_line_and_the_message_still_moves_on() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.outbox.add(add(1, "text", new OutboxFile(id(), "gone.pdf", "application/pdf", 1L)));

        h.tick();

        assertTrue(h.guild.uploads.isEmpty(), "nothing to upload");
        assertTrue(h.guild.texts(h.channelOf(ticket)).contains("File unavailable"), "the channel says so");
        assertEquals(1, ticket.relayedSeq, "a 404 does not block the outbox");
    }

    @Test
    void a_scanner_outage_holds_the_message_and_the_next_pass_posts_it_once() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String attachment = id();
        h.backend.files.put(attachment, new byte[]{7});
        ticket.outbox.add(add(1, "with file", new OutboxFile(attachment, "a.pdf", "application/pdf", 1L)));
        h.backend.failing.add("fetch");

        h.tick();

        assertEquals(0, ticket.relayedSeq, "not acknowledged");
        assertTrue(relayedTexts(h, ticket).isEmpty(), "nothing posted before the bytes are there");
        assertTrue(h.guild.uploads.isEmpty(), "no upload");

        h.backend.failing.clear();
        h.tick();

        assertEquals(List.of("with file"), relayedTexts(h, ticket), "posted once after the outage");
        assertEquals(1, h.guild.uploads.size(), "uploaded once");
        assertEquals(1, ticket.relayedSeq, "acknowledged now");
    }

    @Test
    void an_image_share_is_posted_as_a_labelled_file_and_linked_by_the_share_id() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String share = id();
        h.backend.files.put(share, new byte[]{9, 9});
        ticket.outbox.add(new OutboxItem(1, OutboxKind.SHARE, share, null, null, null, List.of(new OutboxFile(share, "screenshot.png", "image/png", 2L))));

        h.tick();

        FakeGuild.Upload upload = h.guild.uploads.getFirst();
        assertEquals("Evidence shared: screenshot.png", upload.caption().replace("\\", ""), "the label");
        assertEquals("screenshot.png", upload.name(), "the file name");
        assertEquals(Long.toString(upload.messageId()), ticket.relayed.get(share), "linked: share id to the Discord message");
        assertEquals(1, ticket.relayedSeq, "acknowledged");
    }

    @Test
    void an_excerpt_share_is_posted_as_a_code_block_of_the_lines() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String share = id();
        ticket.outbox.add(new OutboxItem(1, OutboxKind.SHARE, share, null, "Player 2: hi\nYou: ok", null, List.of()));

        h.tick();

        List<String> texts = h.guild.texts(h.channelOf(ticket));
        assertEquals("```\nPlayer 2: hi\nYou: ok\n```", texts.getLast(), "a fenced block of the lines");
        assertNotNull(ticket.relayed.get(share), "linked by the share id");
        assertTrue(h.guild.uploads.isEmpty(), "no file for an excerpt");
    }

    @Test
    void a_share_with_neither_lines_nor_file_says_it_is_unavailable() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String share = id();
        ticket.outbox.add(new OutboxItem(1, OutboxKind.SHARE, share, null, null, null, List.of()));

        h.tick();

        assertEquals("Evidence share unavailable", h.guild.texts(h.channelOf(ticket)).getLast(), "the line");
        assertEquals(1, ticket.relayedSeq, "acknowledged");
    }

    @Test
    void a_share_whose_image_cannot_be_fetched_during_an_outage_is_held() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String share = id();
        h.backend.files.put(share, new byte[]{1});
        ticket.outbox.add(new OutboxItem(1, OutboxKind.SHARE, share, null, null, null, List.of(new OutboxFile(share, "a.png", "image/png", 1L))));
        h.backend.failing.add("fetch");

        h.tick();

        assertEquals(0, ticket.relayedSeq, "not acknowledged while the store is down");
        assertTrue(h.guild.uploads.isEmpty(), "nothing posted");

        h.backend.failing.clear();
        h.tick();

        assertEquals(1, h.guild.uploads.size(), "posted on the next pass");
        assertEquals(1, ticket.relayedSeq, "acknowledged");
    }

    @Test
    void a_revoked_share_deletes_the_discord_copy_and_posts_the_withdrawn_line() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        String share = id();
        ticket.outbox.add(new OutboxItem(1, OutboxKind.SHARE, share, null, "Player 2: hi", null, List.of()));
        h.tick();
        String copy = ticket.relayed.get(share);
        long channel = h.channelOf(ticket);
        assertTrue(h.guild.messages(channel).stream().anyMatch(m -> Long.toString(m.id).equals(copy)), "the copy is in the channel");

        ticket.outbox.add(new OutboxItem(2, OutboxKind.SHARE_REVOKED, share, null, null, copy, List.of()));
        h.tick();

        assertFalse(h.guild.messages(channel).stream().anyMatch(m -> Long.toString(m.id).equals(copy)), "the copy is deleted");
        assertEquals("Evidence share withdrawn", h.guild.texts(channel).getLast(), "the system line");
        assertEquals(2, ticket.relayedSeq, "acknowledged");
    }

    @Test
    void a_revoke_without_a_discord_copy_only_posts_the_line() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        ticket.outbox.add(new OutboxItem(1, OutboxKind.SHARE_REVOKED, id(), null, null, null, List.of()));

        h.tick();

        assertEquals("Evidence share withdrawn", h.guild.texts(h.channelOf(ticket)).getLast(), "the line");
        assertEquals(1, ticket.relayedSeq, "acknowledged");
        assertFalse(h.guild.calls.contains("deleteMessage"), "nothing to delete");
    }

    @Test
    void a_redaction_still_deletes_the_discord_message() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        OutboxItem added = add(1, "secret");
        ticket.outbox.add(added);
        h.tick();
        String copy = ticket.relayed.get(added.messageId());

        ticket.outbox.add(new OutboxItem(2, OutboxKind.REDACT, added.messageId(), null, null, copy));
        h.tick();

        assertTrue(relayedTexts(h, ticket).isEmpty(), "the message is gone");
        assertEquals(2, ticket.relayedSeq, "acknowledged");
    }
}
