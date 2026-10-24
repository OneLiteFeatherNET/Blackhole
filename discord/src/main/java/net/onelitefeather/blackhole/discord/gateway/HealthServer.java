package net.onelitefeather.blackhole.discord.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A tiny endpoint for the container: {@code GET /healthz} answers 200 while the bot is connected to Discord and has no
 * permission problem, and 503 otherwise with the problems named (a category and a permission, never content);
 * {@code GET /metrics} answers the counters in the Prometheus text format. It exposes nothing else.
 */
public final class HealthServer implements AutoCloseable {

    /**
     * @param connected whether the gateway connection is up
     * @param problems  what the permission self-check found missing
     */
    public record Status(boolean connected, @NotNull List<String> problems) {

        public boolean healthy() {
            return this.connected && this.problems.isEmpty();
        }
    }

    private final HttpServer server;

    /**
     * @param port    the port to listen on, 0 picks a free one
     * @param healthy whether the bot is healthy right now
     */
    public HealthServer(int port, @NotNull BooleanSupplier healthy) throws IOException {
        this(port, () -> new Status(healthy.getAsBoolean(), List.of()), () -> "");
    }

    /**
     * @param port    the port to listen on, 0 picks a free one
     * @param status  the bot's state right now
     * @param metrics the counters in the Prometheus text format
     */
    public HealthServer(int port, @NotNull Supplier<Status> status, @NotNull Supplier<String> metrics) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/healthz", exchange -> {
            Status current = status.get();
            StringBuilder body = new StringBuilder(current.healthy() ? "ok" : "unavailable");
            if (!current.connected()) {
                body.append("\nnot connected to Discord");
            }
            current.problems().forEach(problem -> body.append('\n').append(problem));
            respond(exchange, current.healthy() ? 200 : 503, body.toString());
        });
        this.server.createContext("/metrics", exchange -> respond(exchange, 200, metrics.get()));
    }

    private static void respond(HttpExchange exchange, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
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
