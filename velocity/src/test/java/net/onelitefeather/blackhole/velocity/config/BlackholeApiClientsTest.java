package net.onelitefeather.blackhole.velocity.config;

import com.sun.net.httpserver.HttpServer;
import net.onelitefeather.blackhole.client.api.SectionApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackholeApiClientsTest {

    @Test
    void aPlainHostAndPortIsUsedAsIs() {
        ApiClient client = BlackholeApiClients.create("http://localhost:8080");

        assertEquals("http://localhost:8080", client.getBaseUri());
    }

    @Test
    void aPathPrefixIsKept() {
        ApiClient client = BlackholeApiClients.create("https://bans.example.net/api");

        assertEquals("https://bans.example.net/api", client.getBaseUri());
    }

    @Test
    void trailingSlashesAreDropped() {
        assertEquals("http://localhost:8080", BlackholeApiClients.create("http://localhost:8080/").getBaseUri());
        assertEquals("https://bans.example.net/api",
                BlackholeApiClients.create("https://bans.example.net/api//").getBaseUri());
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals("http://localhost:8080", BlackholeApiClients.create("  http://localhost:8080 \n").getBaseUri());
    }

    @Test
    void aUrlWithoutSchemeIsRejectedWithAClearMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BlackholeApiClients.create("localhost:8080"));

        assertTrue(e.getMessage().contains("localhost:8080"), "message names the bad value: " + e.getMessage());
    }

    @Test
    void aNonHttpSchemeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlackholeApiClients.create("ftp://localhost"));
    }

    @Test
    void aMissingHostIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlackholeApiClients.create("http://"));
    }

    @Test
    void aQueryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlackholeApiClients.create("http://localhost:8080?x=1"));
    }

    @Test
    void aGeneratedApiCallReachesTheConfiguredServerUnderThePathPrefix() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/api/";
            new SectionApi(BlackholeApiClients.create(url)).listSections();
        } finally {
            server.stop(0);
        }

        String path = requestedPath.get();
        assertTrue(path != null && path.startsWith("/api/") && !path.startsWith("/api//"),
                "request reached the server under /api: " + path);
    }
}
