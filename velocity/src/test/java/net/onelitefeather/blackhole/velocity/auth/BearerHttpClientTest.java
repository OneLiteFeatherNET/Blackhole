package net.onelitefeather.blackhole.velocity.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BearerHttpClientTest {

    /** Hands out t1, t2, ... and records what was reported as rejected. */
    private static final class CountingTokens implements TokenSource {
        final List<String> issued = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        private String current;

        @Override
        public synchronized String token() {
            if (this.current == null) {
                this.current = "t" + (this.issued.size() + 1);
                this.issued.add(this.current);
            }
            return this.current;
        }

        @Override
        public synchronized void rejected(String token) {
            this.rejected.add(token);
            if (token.equals(this.current)) {
                this.current = null;
            }
        }
    }

    private static final HttpRequest REQUEST = HttpRequest.newBuilder(URI.create("http://backend.test/punishment"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"))
            .build();

    private static String bearer(HttpRequest request) {
        return String.join("|", request.headers().allValues("Authorization"));
    }

    private static HttpResponse<String> send(HttpClient client) throws Exception {
        return client.send(REQUEST, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyRequestCarriesTheBearerToken() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        HttpResponse<String> response = send(new BearerHttpClient(transport, new CountingTokens()));

        assertEquals(200, response.statusCode(), "the answer is passed through");
        assertEquals("Bearer t1", bearer(transport.requests().getFirst()), "authorization header");
    }

    @Test
    void theRequestBodyAndMethodAreKept() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");

        send(new BearerHttpClient(transport, new CountingTokens()));

        assertEquals("POST", transport.requests().getFirst().method(), "method");
        assertEquals("{\"a\":1}", transport.bodyOf(0), "body");
    }

    @Test
    void aRejectedTokenIsReplacedAndTheRequestRepeatedOnce() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(401, "").answer(200, "{}");
        CountingTokens tokens = new CountingTokens();

        HttpResponse<String> response = send(new BearerHttpClient(transport, tokens));

        assertEquals(200, response.statusCode(), "the repeated call's answer is returned");
        assertEquals(List.of("Bearer t1", "Bearer t2"),
                transport.requests().stream().map(BearerHttpClientTest::bearer).toList(), "second try uses the fresh token");
        assertEquals(List.of("t1"), tokens.rejected, "the refused token was reported");
        assertEquals("{\"a\":1}", transport.bodyOf(1), "the body is sent again");
    }

    @Test
    void aSecond401IsReturnedAndNotRetriedAgain() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(401, "");
        CountingTokens tokens = new CountingTokens();

        HttpResponse<String> response = send(new BearerHttpClient(transport, tokens));

        assertEquals(401, response.statusCode(), "the second refusal reaches the caller");
        assertEquals(2, transport.requests().size(), "exactly one retry, no loop");
        assertEquals(List.of("t1", "t2"), tokens.issued, "exactly two tokens were fetched");
    }

    @Test
    void otherErrorsAreNotRetried() throws Exception {
        for (int status : new int[]{403, 404, 500}) {
            FakeHttpClient transport = new FakeHttpClient().answer(status, "");

            HttpResponse<String> response = send(new BearerHttpClient(transport, new CountingTokens()));

            assertEquals(status, response.statusCode(), "status " + status + " is passed through");
            assertEquals(1, transport.requests().size(), "status " + status + " is not retried");
        }
    }

    @Test
    void anAuthorizationHeaderAlreadyOnTheRequestIsReplaced() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(401, "").answer(200, "{}");
        HttpRequest withHeader = HttpRequest.newBuilder(REQUEST, (n, v) -> true).header("Authorization", "Bearer stale").build();

        new BearerHttpClient(transport, new CountingTokens()).send(withHeader, HttpResponse.BodyHandlers.ofString());

        assertEquals(List.of("Bearer t1", "Bearer t2"),
                transport.requests().stream().map(BearerHttpClientTest::bearer).toList(), "one header each, never the stale one");
    }

    @Test
    void aMissingTokenFailsTheCallWithoutSendingAnything() {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");
        BearerHttpClient client = new BearerHttpClient(transport, TokenSource.unavailable("no credentials"));

        TokenException e = assertThrows(TokenException.class, () -> send(client));

        assertEquals("no credentials", e.getMessage(), "the reason reaches the caller");
        assertEquals(0, transport.requests().size(), "nothing was sent");
    }

    @Test
    void anAsyncRequestIsRetriedOnceAfter401() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(401, "");
        CountingTokens tokens = new CountingTokens();

        HttpResponse<String> response = new BearerHttpClient(transport, tokens)
                .sendAsync(REQUEST, HttpResponse.BodyHandlers.ofString()).get();

        assertEquals(401, response.statusCode(), "the second refusal reaches the caller");
        assertEquals(2, transport.requests().size(), "exactly one retry, no loop");
    }
}
