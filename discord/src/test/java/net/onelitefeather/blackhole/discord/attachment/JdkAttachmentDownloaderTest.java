package net.onelitefeather.blackhole.discord.attachment;

import net.onelitefeather.blackhole.discord.attachment.AttachmentDownloader.Download;
import net.onelitefeather.blackhole.discord.auth.FakeHttpClient;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The real downloader over a scripted HTTP client: no network. */
class JdkAttachmentDownloaderTest {

    private static final URI URL = URI.create("https://cdn.discordapp.com/attachments/1/2/a.png");

    @Test
    void a_200_answer_within_the_cap_is_returned_whole() {
        FakeHttpClient http = new FakeHttpClient().answer(200, "abcd");

        Download download = new JdkAttachmentDownloader(http).get(URL, 4);

        assertArrayEquals("abcd".getBytes(), ((Download.Bytes) download).data(), "a body of exactly the cap is fine");
        assertEquals(URL, http.requests().getFirst().uri(), "the url asked for");
        assertEquals("GET", http.requests().getFirst().method(), "a plain GET");
    }

    @Test
    void a_body_longer_than_the_cap_is_cut_off_as_too_large() {
        FakeHttpClient http = new FakeHttpClient().answer(200, "abcde");

        assertEquals(new Download.TooLarge(), new JdkAttachmentDownloader(http).get(URL, 4), "one byte over");
    }

    @Test
    void a_redirect_answer_is_reported_and_no_second_request_is_made() {
        FakeHttpClient http = new FakeHttpClient().answer(302, "").answer(200, "abcd");

        assertEquals(new Download.Redirected(), new JdkAttachmentDownloader(http).get(URL, 10), "redirect");
        assertEquals(1, http.requests().size(), "the location was not followed");
    }

    @Test
    void any_other_status_is_a_failure() {
        assertEquals(new Download.Failed(), new JdkAttachmentDownloader(new FakeHttpClient().answer(404, "")).get(URL, 10), "404");
        assertEquals(new Download.Failed(), new JdkAttachmentDownloader(new FakeHttpClient().answer(500, "")).get(URL, 10), "500");
    }
}
