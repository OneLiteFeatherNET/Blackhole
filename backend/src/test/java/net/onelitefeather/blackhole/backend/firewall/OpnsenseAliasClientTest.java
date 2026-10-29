package net.onelitefeather.blackhole.backend.firewall;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The OPNsense adapter against an in-process HTTP stub on the loopback interface; no real firewall is contacted. */
class OpnsenseAliasClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private int status = 200;
    private String answer = "{\"result\":\"saved\"}";
    private final CountDownLatch release = new CountDownLatch(1);
    private boolean hang;

    @BeforeEach
    void startStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", exchange -> {
            this.requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            this.authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            this.bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (this.hang) {
                awaitRelease();
            }
            byte[] bytes = this.answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(this.status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        this.server.start();
    }

    @AfterEach
    void stopStub() {
        this.release.countDown();
        this.server.stop(0);
    }

    private void awaitRelease() {
        try {
            this.release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    private OpnsenseAliasClient client(String key, String secret, Duration timeout) {
        return new OpnsenseAliasClient(baseUrl(), key, secret, timeout);
    }

    @Test
    void addSendsTheRangeAsAddressJsonToTheAliasPath() {
        client("key", "value", Duration.ofSeconds(5)).add("blackhole_ipban", "203.0.113.7/32");

        assertEquals("POST /api/firewall/alias_util/add/blackhole_ipban", this.requests.getFirst(), "the add path");
        assertEquals("{\"address\":\"203.0.113.7/32\"}", this.bodies.getFirst(), "the verified body shape");
    }

    @Test
    void theKeyAndSecretAreSentAsBasicCredentials() {
        client("key", "value", Duration.ofSeconds(5)).reconfigure();

        String expected = "Basic " + Base64.getEncoder().encodeToString("key:value".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, this.authorizations.getFirst(), "the credentials are sent with each request");
    }

    @Test
    void aRefusedCredentialIsAnAuthenticationErrorAndNotRetried() {
        this.status = 401;

        AliasException error = assertThrows(AliasException.class,
                () -> client("key", "value", Duration.ofSeconds(5)).add("blackhole_ipban", "203.0.113.7/32"),
                "a 401 is an error");

        assertEquals("add", error.operation(), "the failing operation is named");
        assertFalse(error.getMessage().contains("value"), "the secret never appears in the message");
        assertEquals(1, this.requests.size(), "the request is sent once");
    }

    @Test
    void aTimedOutRequestIsReportedAsAnError() {
        this.hang = true;

        AliasException error = assertThrows(AliasException.class,
                () -> client("key", "value", Duration.ofMillis(200)).entries("blackhole_ipban"),
                "a request that does not answer in time fails");

        assertEquals("list", error.operation(), "the list operation is named");
    }

    @Test
    void theListAcceptsAnAddressArrayAndIgnoresBlankEntries() {
        this.answer = "{\"address\":[\"203.0.113.7/32\",\"198.51.100.0/24\",\"\"]}";

        Set<String> entries = client("key", "value", Duration.ofSeconds(5)).entries("blackhole_ipban");

        assertEquals(Set.of("203.0.113.7/32", "198.51.100.0/24"), entries, "blank entries are dropped");
    }

    @Test
    void aFailedResultOnAWriteIsAnError() {
        this.answer = "{\"result\":\"failed\"}";

        assertThrows(AliasException.class, () -> client("key", "value", Duration.ofSeconds(5)).delete("blackhole_ipban", "203.0.113.7/32"),
                "an answer that says failed is not success");
    }

    @Test
    void aPlainHttpBaseOutsideTheLoopbackIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> new OpnsenseAliasClient("http://203.0.113.9", "key", "value", Duration.ofSeconds(5)),
                "plain http to a remote host is refused before any request");
        assertTrue(OpnsenseAliasClient.validateBaseUrl("https://fw.example.net") != null, "https is accepted");
    }
}
