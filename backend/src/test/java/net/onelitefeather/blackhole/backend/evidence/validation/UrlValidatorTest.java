package net.onelitefeather.blackhole.backend.evidence.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/replay/1", "http://example.org:8080/a?b=c#d", "HTTPS://EXAMPLE.ORG/x"})
    void absoluteHttpAndHttpsUrlsAreAccepted(String url) {
        assertTrue(UrlValidator.check(url).isEmpty(), url + " should be accepted but: " + UrlValidator.check(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///etc/passwd", "ftp://example.org/x", "data:text/html,hi", "mailto:a@b.c"})
    void otherSchemesAreRejected(String url) {
        assertTrue(UrlValidator.check(url).isPresent(), url + " must be rejected");
    }

    @Test
    void userInfoIsRejected() {
        assertTrue(UrlValidator.check("https://user:secret@example.org/x").orElse("").contains("user info"),
                "credentials in a URL would be stored in plain text");
    }

    @Test
    void aRelativeUrlIsRejected() {
        assertTrue(UrlValidator.check("/replays/1").orElse("").contains("absolute"), "relative URLs are not references");
    }

    @Test
    void aUrlWithoutAHostIsRejected() {
        assertTrue(UrlValidator.check("https:///path").isPresent(), "a host is required");
    }

    @Test
    void whitespaceAndControlCharactersAreRejected() {
        assertTrue(UrlValidator.check("https://example.org/a b").isPresent(), "space");
        assertTrue(UrlValidator.check("https://example.org/a\nb").isPresent(), "newline");
    }

    @Test
    void aUrlLongerThanTheLimitIsRejected() {
        String longUrl = "https://example.org/" + "a".repeat(UrlValidator.MAX_LENGTH);

        assertTrue(UrlValidator.check(longUrl).orElse("").contains("at most"), "over 2048 characters");
        assertTrue(UrlValidator.check("https://example.org/" + "a".repeat(UrlValidator.MAX_LENGTH - 20)).isEmpty(), "just under the limit");
    }

    @Test
    void aBlankOrMissingReferenceIsRejected() {
        assertTrue(UrlValidator.check(null).isPresent());
        assertTrue(UrlValidator.check("  ").isPresent());
    }

    @Test
    void theValidatorHasNoWayToMakeANetworkRequest() throws IOException {
        // Links are stored, never fetched. The class file must not reference any client or resolver.
        try (InputStream in = UrlValidator.class.getResourceAsStream("UrlValidator.class")) {
            String constantPool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String forbidden : new String[]{"java/net/http", "HttpClient", "HttpURLConnection", "openConnection", "openStream",
                    "InetAddress", "java/net/Socket", "URLConnection"}) {
                assertFalse(constantPool.contains(forbidden), "UrlValidator must not reference " + forbidden);
            }
        }
    }
}
