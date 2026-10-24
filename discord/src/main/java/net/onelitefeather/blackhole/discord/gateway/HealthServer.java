package net.onelitefeather.blackhole.discord.gateway;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * A tiny health endpoint for the container: {@code GET /healthz} answers 200 while the bot is connected
 * to Discord and 503 otherwise. It exposes nothing else.
 */
public final class HealthServer implements AutoCloseable {

    private final HttpServer server;

    /**
     * @param port    the port to listen on, 0 picks a free one
     * @param healthy whether the bot is healthy right now
     */
    public HealthServer(int port, @NotNull BooleanSupplier healthy) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/healthz", exchange -> {
            boolean up = healthy.getAsBoolean();
            byte[] body = (up ? "ok" : "unavailable").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(up ? 200 : 503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    public void start() {
        this.server.start();
    }

    /** @return the port the server listens on */
    public int port() {
        return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
