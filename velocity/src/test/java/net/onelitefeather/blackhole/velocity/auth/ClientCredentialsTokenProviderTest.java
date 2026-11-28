package net.onelitefeather.blackhole.velocity.auth;

import net.onelitefeather.blackhole.velocity.config.AuthConfig;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCredentialsTokenProviderTest {

    private static final String SECRET = "s3cr3t/value+with=chars";

    /** Answers the token requests from a script and optionally holds the first one until released. */
    private static final class FakeTransport implements TokenTransport {
        private final List<Response> script = new ArrayList<>();
        private final List<URI> uris = new ArrayList<>();
        private final List<String> forms = new ArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;
        private IOException failure;

        FakeTransport(CountDownLatch release) {
            this.release = release;
        }

        FakeTransport() {
            this(null);
        }

        FakeTransport answer(int status, String body) {
            this.script.add(new Response(status, body));
            return this;
        }

        FakeTransport ok(String token, long expiresIn) {
            return answer(200, "{\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + ",\"access_token\":\"" + token + "\"}");
        }

        FakeTransport failWith(IOException failure) {
            this.failure = failure;
            return this;
        }

        @Override
        public Response post(URI uri, String form) throws IOException {
            int call;
            synchronized (this) {
                this.uris.add(uri);
                this.forms.add(form);
                call = this.uris.size();
            }
            this.entered.countDown();
            if (this.release != null) {
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (this.failure != null) {
                throw this.failure;
            }
            return this.script.get(Math.min(call, this.script.size()) - 1);
        }

        synchronized int calls() {
            return this.uris.size();
        }
    }

    private static AuthConfig auth() {
        return new AuthConfig().withEnvironment(Map.of(
                AuthConfig.ENV_TENANT_ID, "tenant-1",
                AuthConfig.ENV_CLIENT_ID, "client-1",
                AuthConfig.ENV_CLIENT_SECRET, SECRET,
                AuthConfig.ENV_SCOPE, "api://api-1/.default")::get);
    }

    @Test
    void theRequestIsAClientCredentialsGrantToTheTenantsEndpoint() throws Exception {
        FakeTransport transport = new FakeTransport().ok("tok", 3600);

        new ClientCredentialsTokenProvider(auth(), transport, new MutableClock()).token();

        assertEquals(URI.create("https://login.microsoftonline.com/tenant-1/oauth2/v2.0/token"), transport.uris.getFirst(),
                "default endpoint of the tenant");
        assertEquals("grant_type=client_credentials&client_id=client-1&client_secret="
                        + "s3cr3t%2Fvalue%2Bwith%3Dchars&scope=api%3A%2F%2Fapi-1%2F.default",
                transport.forms.getFirst(), "form body, url-encoded");
    }

    @Test
    void aTokenIsReusedInsideItsLifetime() throws Exception {
        FakeTransport transport = new FakeTransport().ok("tok", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());

        String first = provider.token();
        String second = provider.token();

        assertEquals(first, second, "same token");
        assertEquals(1, transport.calls(), "one token request for two calls");
    }

    @Test
    void aTokenIsStillReusedJustBeforeTheSkewStarts() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport transport = new FakeTransport().ok("tok", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, clock);
        provider.token();

        clock.advance(Duration.ofSeconds(3600 - 61));
        provider.token();

        assertEquals(1, transport.calls(), "61 seconds before expiry the token is still used");
    }

    @Test
    void aTokenIsRefreshedSixtySecondsBeforeItExpires() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport transport = new FakeTransport().ok("old", 3600).ok("new", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, clock);
        provider.token();

        clock.advance(Duration.ofSeconds(3600).minus(ClientCredentialsTokenProvider.SKEW));

        assertEquals("new", provider.token(), "within 60 seconds of expiry a new token is requested first");
        assertEquals(2, transport.calls(), "two token requests");
    }

    @Test
    void concurrentCallersCauseOneTokenRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport(release).ok("tok", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(provider::token));
            }
            assertTrue(transport.entered.await(10, TimeUnit.SECONDS), "the first request is in flight");
            release.countDown();

            for (Future<String> result : results) {
                assertEquals("tok", result.get(10, TimeUnit.SECONDS), "every caller gets the token");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, transport.calls(), "eight callers, one token request");
    }

    @Test
    void aRejectedTokenIsReplacedOnTheNextCall() throws Exception {
        FakeTransport transport = new FakeTransport().ok("old", 3600).ok("new", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());
        String old = provider.token();

        provider.rejected(old);

        assertEquals("new", provider.token(), "a rejected token is not handed out again");
    }

    @Test
    void aLateRejectionOfAnOlderTokenKeepsTheCurrentOne() throws Exception {
        FakeTransport transport = new FakeTransport().ok("current", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());
        provider.token();

        provider.rejected("an-older-token");

        assertEquals("current", provider.token(), "the current token survives");
        assertEquals(1, transport.calls(), "no extra token request");
    }

    @Test
    void aRefusedRequestSurfacesStatusAndReasonWithoutTheSecret() {
        FakeTransport transport = new FakeTransport().answer(401,
                "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\"}");
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());

        TokenException e = assertThrows(TokenException.class, provider::token);

        assertTrue(e.getMessage().contains("HTTP 401") && e.getMessage().contains("invalid_client")
                        && e.getMessage().contains("AADSTS7000215"),
                "status and reason are named: " + e.getMessage());
        assertFalse(e.getMessage().contains(SECRET), "the secret never appears in the message");
    }

    @Test
    void anUnreachableEndpointSurfacesAsATokenExceptionWithItsCause() {
        IOException cause = new IOException("connection refused");
        FakeTransport transport = new FakeTransport().failWith(cause);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());

        TokenException e = assertThrows(TokenException.class, provider::token);

        assertEquals(cause, e.getCause(), "the transport failure is kept as the cause");
        assertTrue(e.getMessage().contains("connection refused"), "the reason is named: " + e.getMessage());
    }

    @Test
    void aResponseWithoutATokenIsRejected() {
        FakeTransport transport = new FakeTransport().answer(200, "{\"expires_in\":3600}");
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());

        TokenException e = assertThrows(TokenException.class, provider::token);

        assertTrue(e.getMessage().contains("access_token"), "names what is missing: " + e.getMessage());
    }

    @Test
    void aResponseThatIsNotJsonIsRejected() {
        FakeTransport transport = new FakeTransport().answer(200, "<html>proxy login</html>");
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());

        assertThrows(TokenException.class, provider::token);
    }

    @Test
    void aFailedRequestIsNotRememberedAndTheNextCallTriesAgain() throws Exception {
        FakeTransport transport = new FakeTransport().answer(503, "").ok("tok", 3600);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock());
        assertThrows(TokenException.class, provider::token);

        assertEquals("tok", provider.token(), "the second call succeeds");
        assertEquals(2, transport.calls(), "the second call sent a new request");
    }

    @Test
    void noRequestCarriesTheSecretToAnHttpEndpoint() {
        AuthConfig http = new AuthConfig().withEnvironment(Map.of(
                AuthConfig.ENV_TOKEN_URL, "http://idp.example.com/token", AuthConfig.ENV_CLIENT_ID, "c",
                AuthConfig.ENV_CLIENT_SECRET, SECRET, AuthConfig.ENV_SCOPE, "x")::get);
        FakeTransport transport = new FakeTransport().ok("tok", 3600);

        assertThrows(IllegalArgumentException.class,
                () -> new ClientCredentialsTokenProvider(http, transport, new MutableClock()).token(),
                "a provider for an http endpoint cannot be built");

        assertEquals(0, transport.calls(), "nothing was sent to the http endpoint");
    }

    @Test
    void incompleteCredentialsAreRefusedAtConstruction() {
        AuthConfig incomplete = new AuthConfig();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ClientCredentialsTokenProvider(incomplete, new FakeTransport(), new MutableClock()));

        assertTrue(e.getMessage().contains("clientSecret"), "names what is missing: " + e.getMessage());
    }

    /**
     * Answers the requests before {@code holdFrom} at once. From {@code holdFrom} on, a request waits until
     * {@link #release} is counted down, and {@link #held} counts down when the first such request arrives.
     */
    private static final class GatedTransport implements TokenTransport {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final String first;
        private final String later;
        private final int holdFrom;
        private final AtomicInteger calls = new AtomicInteger();

        GatedTransport(String first, String later, int holdFrom) {
            this.first = first;
            this.later = later;
            this.holdFrom = holdFrom;
        }

        @Override
        public Response post(URI uri, String form) {
            int call = this.calls.incrementAndGet();
            if (call >= this.holdFrom) {
                this.held.countDown();
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new Response(200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\""
                    + (call == 1 ? this.first : this.later) + "\"}");
        }
    }

    @Test
    void aCallerWhoseTokenIsStillValidDoesNotWaitForARefreshThatIsRunning() throws Exception {
        MutableClock clock = new MutableClock();
        GatedTransport transport = new GatedTransport("old", "new", 2);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, clock);
        provider.token();
        clock.advance(Duration.ofSeconds(3600).minus(ClientCredentialsTokenProvider.SKEW));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> refreshing = pool.submit(provider::token);
            assertTrue(transport.held.await(10, TimeUnit.SECONDS), "the refresh is in flight");

            String meanwhile = assertTimeoutPreemptively(Duration.ofSeconds(5), provider::token,
                    "a caller whose token has not expired must not wait for the refresh");

            assertEquals("old", meanwhile, "the token that is still valid is handed out");
            transport.release.countDown();
            assertEquals("new", refreshing.get(10, TimeUnit.SECONDS), "the refresh completes with the new token");
        } finally {
            transport.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aCallerWithoutAValidTokenGivesUpAtItsWaitBoundWhileARefreshIsStillRunning() throws Exception {
        GatedTransport transport = new GatedTransport("tok", "tok", 1);
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(auth(), transport, new MutableClock(),
                Duration.ofMillis(1));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(provider::token);
            assertTrue(transport.held.await(10, TimeUnit.SECONDS), "the first request is in flight");

            TokenException e = assertThrows(TokenException.class, provider::token,
                    "a caller waits only up to its bound for a request another caller started");

            assertTrue(e.getMessage().contains("still in progress"), "says why it gave up: " + e.getMessage());
        } finally {
            transport.release.countDown();
            pool.shutdownNow();
        }
    }
}
