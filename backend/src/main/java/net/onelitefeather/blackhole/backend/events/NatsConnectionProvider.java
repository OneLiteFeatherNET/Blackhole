package net.onelitefeather.blackhole.backend.events;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the one NATS connection of this process. jnats' own connect blocks (and with unlimited
 * reconnects never returns) while the server is unreachable, so it runs on a background thread started
 * on first use; the connection reconnects forever once it exists. A NATS outage at startup or later
 * therefore never stops the application, and no request thread ever waits: {@link #connection()} fails
 * at once while there is no connection, {@link #await(Duration)} is for threads that may wait. An
 * unreadable credentials file is logged and the connection is tried without it.
 */
@Singleton
public class NatsConnectionProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NatsConnectionProvider.class);

    private final NatsSettings settings;
    private final CompletableFuture<Connection> ready = new CompletableFuture<>();
    private Thread connector;
    private boolean closed;

    public NatsConnectionProvider(NatsSettings settings) {
        this.settings = settings;
    }

    private synchronized void start() {
        if (this.connector != null || this.closed) {
            return;
        }
        this.connector = Thread.ofVirtual().name("nats-connect").start(() -> {
            try {
                Connection opened = Nats.connectReconnectOnConnect(options(this.settings));
                if (!this.ready.complete(opened)) {
                    closeQuietly(opened);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.ready.completeExceptionally(e);
            } catch (Exception e) {
                LOGGER.error("Could not set up the NATS connection: {}", e.getMessage());
                this.ready.completeExceptionally(e);
            }
        });
    }

    /**
     * @return the connection if it is up, without waiting
     * @throws IllegalStateException while there is no connection yet (or the setup failed)
     */
    public Connection connection() {
        start();
        Connection connection = this.ready.isCompletedExceptionally() ? null : this.ready.getNow(null);
        if (connection == null) {
            throw new IllegalStateException("NATS is not connected yet");
        }
        return connection;
    }

    /**
     * Waits for the first connection; for threads that are allowed to block, never for request threads.
     *
     * @throws IllegalStateException if it did not come up within {@code timeout}
     */
    public Connection await(Duration timeout) {
        start();
        try {
            return this.ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            throw new IllegalStateException("NATS is not connected yet", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for NATS", e);
        }
    }

    static Options options(NatsSettings settings) {
        Options.Builder builder = new Options.Builder()
                .servers(settings.url().split("\\s*,\\s*"))
                .connectionName(settings.connectionName())
                .connectionTimeout(settings.connectTimeout())
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(2))
                .connectionListener(LOG_CONNECTION)
                .errorListener(LOG_ERRORS);
        String credsFile = settings.credsFile();
        if (credsFile != null && !credsFile.isBlank()) {
            if (Files.isReadable(Path.of(credsFile))) {
                builder.credentialPath(credsFile);
            } else {
                LOGGER.error("The NATS credentials file {} is missing or unreadable; connecting without credentials", credsFile);
            }
        }
        return builder.build();
    }

    @PreDestroy
    void close() {
        Thread running;
        synchronized (this) {
            this.closed = true;
            running = this.connector;
        }
        if (running != null) {
            running.interrupt();
        }
        this.ready.completeExceptionally(new IllegalStateException("closed"));
        Connection connection = this.ready.isCompletedExceptionally() ? null : this.ready.getNow(null);
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final ConnectionListener LOG_CONNECTION = (conn, type) ->
            LOGGER.info("NATS connection event: {}", type);

    private static final ErrorListener LOG_ERRORS = new ErrorListener() {
        @Override
        public void errorOccurred(Connection conn, String error) {
            LOGGER.warn("NATS error: {}", error);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            LOGGER.warn("NATS exception: {}", exp.getMessage());
        }
    };
}
