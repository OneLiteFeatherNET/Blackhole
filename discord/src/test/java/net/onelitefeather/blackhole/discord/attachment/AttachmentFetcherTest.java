package net.onelitefeather.blackhole.discord.attachment;

import net.onelitefeather.blackhole.discord.attachment.AttachmentDownloader.Download;
import net.onelitefeather.blackhole.discord.attachment.AttachmentFetcher.Fetched;
import net.onelitefeather.blackhole.discord.testing.FakeDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentFetcherTest {

    private static final String CDN = "https://cdn.discordapp.com/attachments/1/2/a.png?ex=1&hm=2";

    private final FakeDownloader downloader = new FakeDownloader();
    private final AttachmentFetcher fetcher = new AttachmentFetcher(this.downloader);

    @Test
    void a_file_on_the_cdn_host_is_downloaded() {
        this.downloader.serve(CDN, new byte[]{1, 2, 3});

        Fetched fetched = this.fetcher.fetch(CDN, 3L);

        assertArrayEquals(new byte[]{1, 2, 3}, ((Fetched.Bytes) fetched).data(), "the bytes");
        assertEquals(AttachmentFetcher.MAX_BYTES, this.downloader.caps.getFirst(), "the streamed cap is handed to the downloader");
    }

    @Test
    void the_media_proxy_host_is_allowed_too() {
        String url = "https://media.discordapp.net/attachments/1/2/a.png";
        this.downloader.serve(url, new byte[]{9});

        assertTrue(this.fetcher.fetch(url, null) instanceof Fetched.Bytes, "media.discordapp.net");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/a.png",
            "https://cdn.discordapp.com.evil.example/a.png",
            "https://evilcdn.discordapp.com/a.png",
            "https://discordapp.com/a.png",
            "https://cdn.discordapp.com@evil.example/a.png",
            "https://user:pw@cdn.discordapp.com/a.png",
            "http://cdn.discordapp.com/a.png",
            "ftp://cdn.discordapp.com/a.png",
            "https://cdn.discordapp.com:8443/a.png",
            "https://127.0.0.1/a.png",
            "//cdn.discordapp.com/a.png",
            "not a url",
            ""
    })
    void any_other_host_scheme_or_port_is_refused_without_a_request(String url) {
        Fetched fetched = this.fetcher.fetch(url, 10L);

        assertEquals(new Fetched.Rejected("FILE_NOT_ACCEPTED"), fetched, "refused: " + url);
        assertTrue(this.downloader.requested.isEmpty(), "nothing was requested");
    }

    @Test
    void a_missing_url_is_refused() {
        assertEquals(new Fetched.Rejected("FILE_NOT_ACCEPTED"), this.fetcher.fetch(null, 1L), "no url");
    }

    @Test
    void a_redirect_is_refused_and_not_followed() {
        this.downloader.answers.put(CDN, new Download.Redirected());

        assertEquals(new Fetched.Rejected("FILE_NOT_ACCEPTED"), this.fetcher.fetch(CDN, 5L), "redirect");
        assertEquals(1, this.downloader.requested.size(), "one request, no second hop");
    }

    @Test
    void a_declared_size_over_10_mib_is_refused_before_any_download() {
        Fetched fetched = this.fetcher.fetch(CDN, AttachmentFetcher.MAX_BYTES + 1);

        assertEquals(new Fetched.Rejected("TOO_LARGE"), fetched, "declared too large");
        assertTrue(this.downloader.requested.isEmpty(), "not downloaded");
    }

    @Test
    void a_declared_size_of_exactly_10_mib_is_downloaded() {
        this.downloader.serve(CDN, new byte[]{1});

        assertTrue(this.fetcher.fetch(CDN, AttachmentFetcher.MAX_BYTES) instanceof Fetched.Bytes, "the cap is inclusive");
    }

    @Test
    void a_stream_that_grows_past_the_cap_is_refused_even_when_the_declared_size_lied() {
        this.downloader.answers.put(CDN, new Download.TooLarge());

        assertEquals(new Fetched.Rejected("TOO_LARGE"), this.fetcher.fetch(CDN, 10L), "streamed too large");
    }

    @Test
    void a_failed_download_is_recorded_as_not_accepted() {
        assertEquals(new Fetched.Rejected("FILE_NOT_ACCEPTED"), this.fetcher.fetch(CDN, 10L), "the downloader fails for an unscripted url");
    }
}
