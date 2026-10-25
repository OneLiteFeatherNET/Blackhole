package net.onelitefeather.blackhole.backend.playerresolver.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback stand-in for api.mojang.com, started per test on an ephemeral port. It answers the
 * name-to-UUID lookup for one known player and records every requested name, so a test can prove
 * the resolver went to the fake and not to the public host.
 */
final class FakeMojang implements Closeable {

    static final String KNOWN_NAME = "Notch";
    static final String KNOWN_UUID = "069a79f444e94726a5befca90e38aaf5";

    private static final String PREFIX = "/users/profiles/minecraft/";

    private final HttpServer server;
    private final List<String> requestedNames = new CopyOnWriteArrayList<>();

    FakeMojang() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext(PREFIX, this::handle);
        this.server.start();
    }

    /** Base URL to configure the resolver with, without a trailing slash. */
    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    /** Every username the resolver asked this fake for, in request order. */
    List<String> requestedNames() {
        return List.copyOf(this.requestedNames);
    }

    @Override
    public void close() {
        this.server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String name = name(exchange.getRequestURI());
        this.requestedNames.add(name);
        if (KNOWN_NAME.equals(name)) {
            respond(exchange, 200, "{\"id\":\"" + KNOWN_UUID + "\",\"name\":\"" + name + "\"}");
        } else {
            // Mojang answers 204 No Content for a name that does not exist.
            respond(exchange, 204, null);
        }
    }

    private static String name(URI uri) {
        return uri.getPath().substring(PREFIX.length());
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        try (exchange) {
            if (json == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
