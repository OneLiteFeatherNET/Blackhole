package net.onelitefeather.blackhole.discord.sync;

import net.onelitefeather.blackhole.discord.attachment.AttachmentDownloader.Download;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Attachment;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestItem;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingAttachment;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Discord to case direction of the appeal chat: Markdown mapping and files of the player and of staff. */
class InboundChatTest {

    private static final String WARNING = "⚠️";

    private static String url(String id) {
        return "https://cdn.discordapp.com/attachments/1/" + id + "/file.png?ex=1";
    }

    private static IncomingAttachment file(String id, String name, long size) {
        return new IncomingAttachment("file", id, name, "image/png", size, 10, 10, url(id));
    }

    private static IngestItem lastIngested(FakeTicketBackend.Rec ticket) {
        return ticket.ingestedItems.getLast();
    }

    @Test
    void the_text_is_mapped_to_the_supported_subset_before_ingest() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        h.guild.say(h.channelOf(ticket), Harness.PLAYER, "__word__ ||secret|| <@123456789012345678>");
        h.drain();

        assertEquals("word secret @user", lastIngested(ticket).text(), "underline, spoiler and mention mapped");
    }

    @Test
    void a_file_of_the_bound_player_is_downloaded_uploaded_and_referenced_by_its_attachment_id() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.serve(url("111"), new byte[]{1, 2, 3});

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "look", List.of(file("111", "shot.png", 3)));
        h.drain();

        assertEquals(1, h.backend.uploads.size(), "one upload");
        FakeTicketBackend.Upload upload = h.backend.uploads.getFirst();
        assertEquals(ticket.id, upload.ticketId(), "to the ticket");
        assertEquals("shot.png", upload.name(), "with the file name");
        assertEquals("111", upload.discordAttachmentId(), "with the Discord attachment id");
        assertEquals(Long.toString(Harness.PLAYER), upload.author(), "with the author's Discord user id");
        assertArrayEquals(new byte[]{1, 2, 3}, upload.data(), "the downloaded bytes");
        Attachment recorded = lastIngested(ticket).attachments().getFirst();
        assertNotNull(recorded.attachmentId(), "the ingest item carries the backend's attachment id");
        assertNull(recorded.rejected(), "not rejected");
        assertTrue(h.guild.reactions.isEmpty(), "no warning");
    }

    @Test
    void a_file_of_a_staff_role_holder_is_offered_to_the_backend_too() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        h.downloader.serve(url("112"), new byte[]{5});

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.STAFF_A, "evidence", List.of(file("112", "a.png", 1)));
        h.drain();

        assertEquals(Long.toString(Harness.STAFF_A), h.backend.uploads.getFirst().author(), "uploaded as the staff member");
    }

    @Test
    void a_backend_refusal_is_recorded_as_rejected_with_its_code_and_the_message_gets_a_warning() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.serve(url("113"), new byte[]{1});
        h.backend.uploadAnswers.put("113", Result.Failed.of(400, "UNSUPPORTED_TYPE"));

        long message = h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "x", List.of(file("113", "a.exe", 1)));
        h.drain();

        Attachment recorded = lastIngested(ticket).attachments().getFirst();
        assertEquals("UNSUPPORTED_TYPE", recorded.rejected(), "the backend's code");
        assertNull(recorded.attachmentId(), "no attachment id");
        assertEquals(1, h.guild.reactions.size(), "one reaction");
        assertEquals(WARNING, h.guild.reactions.getFirst().emoji(), "the warning sign");
        assertEquals(message, h.guild.reactions.getFirst().messageId(), "on the message with the file");
    }

    @Test
    void a_refusal_without_a_code_is_recorded_as_file_not_accepted() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.serve(url("114"), new byte[]{1});
        h.backend.uploadAnswers.put("114", Result.Failed.of(403, null));

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "x", List.of(file("114", "a.png", 1)));
        h.drain();

        assertEquals("FILE_NOT_ACCEPTED", lastIngested(ticket).attachments().getFirst().rejected(), "the default code");
    }

    @Test
    void a_file_on_another_host_is_not_downloaded_and_is_recorded_as_rejected() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        IncomingAttachment evil = new IncomingAttachment("file", "115", "a.png", "image/png", 1L, null, null, "https://evil.example/a.png");

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "x", List.of(evil));
        h.drain();

        assertTrue(h.downloader.requested.isEmpty(), "nothing was requested");
        assertTrue(h.backend.uploads.isEmpty(), "nothing was uploaded");
        assertEquals("FILE_NOT_ACCEPTED", lastIngested(ticket).attachments().getFirst().rejected(), "wrong host");
        assertEquals(1, h.guild.reactions.size(), "warning");
    }

    @Test
    void a_redirect_is_not_followed_and_is_recorded_as_rejected() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.answers.put(url("116"), new Download.Redirected());

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "x", List.of(file("116", "a.png", 1)));
        h.drain();

        assertEquals(1, h.downloader.requested.size(), "one request, no hop");
        assertTrue(h.backend.uploads.isEmpty(), "nothing uploaded");
        assertEquals("FILE_NOT_ACCEPTED", lastIngested(ticket).attachments().getFirst().rejected(), "redirect");
    }

    @Test
    void a_file_over_10_mib_is_not_downloaded_and_is_recorded_as_too_large() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "x", List.of(file("117", "big.png", 10L * 1024 * 1024 + 1)));
        h.drain();

        assertTrue(h.downloader.requested.isEmpty(), "not downloaded");
        assertEquals("TOO_LARGE", lastIngested(ticket).attachments().getFirst().rejected(), "declared too large");
        assertEquals(1, h.guild.reactions.size(), "warning");
    }

    @Test
    void a_stream_that_outgrows_the_cap_is_recorded_as_too_large() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.answers.put(url("118"), new Download.TooLarge());

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "x", List.of(file("118", "a.png", 5)));
        h.drain();

        assertEquals("TOO_LARGE", lastIngested(ticket).attachments().getFirst().rejected(), "streamed too large");
    }

    @Test
    void only_five_files_per_message_are_downloaded_the_rest_are_recorded_as_quota() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        List<IncomingAttachment> files = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String id = "20" + i;
            h.downloader.serve(url(id), new byte[]{(byte) i});
            files.add(file(id, "f" + i + ".png", 1));
        }

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "many", files);
        h.drain();

        assertEquals(5, h.downloader.requested.size(), "five downloads");
        assertEquals(5, h.backend.uploads.size(), "five uploads");
        List<Attachment> recorded = lastIngested(ticket).attachments();
        assertEquals(7, recorded.size(), "all seven are recorded");
        assertEquals("QUOTA", recorded.get(5).rejected(), "the sixth");
        assertEquals("QUOTA", recorded.get(6).rejected(), "the seventh");
        assertNull(recorded.get(4).rejected(), "the fifth is fine");
        assertEquals(1, h.guild.reactions.size(), "one warning for the message");
    }

    @Test
    void a_file_of_someone_else_stays_metadata_only_and_nothing_is_downloaded() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        h.guild.sayWithFiles(h.channelOf(ticket), 999L, "hello", List.of(file("119", "a.png", 1)));
        h.drain();

        assertTrue(h.downloader.requested.isEmpty(), "no download");
        assertTrue(h.backend.uploads.isEmpty(), "no upload");
        Attachment recorded = lastIngested(ticket).attachments().getFirst();
        assertEquals("119", recorded.id(), "the metadata is kept");
        assertNull(recorded.rejected(), "not a rejection");
        assertNull(recorded.attachmentId(), "no attachment id");
        assertTrue(h.guild.reactions.isEmpty(), "no warning");
    }

    @Test
    void a_bot_with_the_staff_role_does_not_get_its_files_downloaded() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.guild.bots.add(777L);
        h.staffRoleFor(777L);

        h.guild.sayWithFiles(h.channelOf(ticket), 777L, "beep", List.of(file("120", "a.png", 1)));
        h.drain();

        assertTrue(h.downloader.requested.isEmpty(), "bots never");
    }

    @Test
    void a_sticker_stays_metadata_even_for_the_player() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "", List.of(new IncomingAttachment("sticker", null, "wave", null, null, null, null)));
        h.drain();

        assertTrue(h.downloader.requested.isEmpty(), "no download for a sticker");
        assertEquals("sticker", lastIngested(ticket).attachments().getFirst().kind(), "kept as metadata");
    }

    @Test
    void a_backend_outage_holds_the_message_and_the_catch_up_ingests_it_with_its_file_afterwards() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.serve(url("301"), new byte[]{4});
        h.backend.failing.add("upload");

        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "first", List.of(file("301", "a.png", 1)));
        h.drain();

        assertTrue(ticket.ingestedItems.isEmpty(), "nothing ingested while the scanner is down");
        assertEquals(0, ticket.cursor, "the cursor did not move");

        h.backend.failing.clear();
        h.ticks(2);

        assertEquals(1, ticket.ingestedItems.size(), "ingested after the outage");
        assertNotNull(lastIngested(ticket).attachments().getFirst().attachmentId(), "with its accepted file");
        assertTrue(ticket.cursor > 0, "the cursor moved");
    }

    @Test
    void a_message_after_a_held_one_waits_so_the_cursor_never_skips_the_held_message() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.downloader.serve(url("302"), new byte[]{4});
        h.backend.failing.add("upload");
        h.guild.sayWithFiles(h.channelOf(ticket), Harness.PLAYER, "held", List.of(file("302", "a.png", 1)));
        h.drain();
        h.backend.failing.clear();

        h.guild.say(h.channelOf(ticket), Harness.PLAYER, "later");
        h.drain();

        assertTrue(ticket.ingestedItems.isEmpty(), "the later message is not ingested ahead of the held one");

        h.ticks(2);

        assertEquals(List.of("held", "later"), ticket.ingestedItems.stream().map(IngestItem::text).toList(), "both, in order");
    }
}
