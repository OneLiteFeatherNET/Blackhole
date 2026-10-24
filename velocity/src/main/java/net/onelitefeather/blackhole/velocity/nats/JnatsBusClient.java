package net.onelitefeather.blackhole.velocity.nats;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link BusClient}: plain jnats, core subscriptions only, never a publish. The connection is
 * opened on its own thread because {@code connectReconnectOnConnect} blocks while the server is down and
 * the proxy's startup must never wait for NATS; after the first connect jnats reconnects without limit.
 */
public final class JnatsBusClient implements BusClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(JnatsBusClient.class);

    private final String url;
    private final String credsFile;
    private final Duration reconnectWait;
    private final Executor connector;
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile Connection connection;

    /**
     * @param url           server URL(s), comma separated
     * @param credsFile     path of the credentials file, empty for none
     * @param reconnectWait the pause between reconnect attempts
     * @param connector     runs the blocking connect off the calling thread
     */
    public JnatsBusClient(@NotNull String url, @NotNull String credsFile, @NotNull Duration reconnectWait, @NotNull Executor connector) {
        this.url = url;
        this.credsFile = credsFile;
        this.reconnectWait = reconnectWait;
        this.connector = connector;
    }

    /** A daemon thread per call, so a pending connect never keeps the JVM alive. */
    public static Executor daemonThread() {
        return task -> {
            Thread thread = new Thread(task, "blackhole-nats-connect");
            thread.setDaemon(true);
            thread.start();
        };
    }

    @Override
    public boolean start(@NotNull Listener listener) {
        if (!this.credsFile.isBlank() && !Files.isReadable(Path.of(this.credsFile))) {
            LOGGER.error("The NATS credentials file {} is missing or unreadable: not connecting, punishments are checked over HTTP only", this.credsFile);
            return false;
        }
        this.connector.execute(() -> run(listener));
        return true;
    }

    private void run(Listener listener) {
        boolean[] subscribed = {false};
        Options options = options(listener, subscribed);
        while (this.closed.getCount() > 0) {
            try {
                Connection opened = Nats.connectReconnectOnConnect(options);
                if (this.closed.getCount() == 0) {
                    opened.close();
                    return;
                }
                this.connection = opened;
                Dispatcher dispatcher = opened.createDispatcher(message -> listener.message(message.getSubject(), message.getData()));
                for (String subject : Subjects.SUBSCRIPTIONS) {
                    dispatcher.subscribe(subject);
                }
                synchronized (subscribed) {
                    subscribed[0] = true;
                }
                if (opened.getStatus() == Connection.Status.CONNECTED) {
                    listener.connected();
                }
                return;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not connect to NATS at {}: {}", redact(this.url), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (this.closed.await(Math.max(this.reconnectWait.toMillis(), 1000), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Options options(Listener listener, boolean[] subscribed) {
        Options.Builder builder = new Options.Builder()
                .servers(this.url.split("\\s*,\\s*"))
                .connectionName("blackhole-velocity")
                .maxReconnects(-1)
                .reconnectWait(this.reconnectWait)
                .connectionTimeout(Duration.ofSeconds(5))
                .connectionListener(connectionListener(listener, subscribed))
                .errorListener(ERRORS);
        if (!this.credsFile.isBlank()) {
            builder.credentialPath(this.credsFile);
        }
        return builder.build();
    }

    private static ConnectionListener connectionListener(Listener listener, boolean[] subscribed) {
        return (conn, type) -> {
            LOGGER.debug("NATS connection event: {}", type);
            switch (type) {
                // After a reconnect the server has not got our subscriptions yet: keep distrusting until RESUBSCRIBED.
                case DISCONNECTED, CLOSED, RECONNECTED -> listener.disconnected();
                case CONNECTED, RESUBSCRIBED -> {
                    synchronized (subscribed) {
                        if (subscribed[0]) {
                            listener.connected();
                        }
                    }
                }
                default -> {
                }
            }
        };
    }

    private static final ErrorListener ERRORS = new ErrorListener() {
        @Override
        public void errorOccurred(Connection conn, String error) {
            LOGGER.warn("NATS error: {}", error);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            LOGGER.warn("NATS exception: {}", exp.getMessage());
        }
    };

    /** The URL without any user info, which may hold a secret. */
    static String redact(String url) {
        return url.replaceAll("://[^/@\\s]*@", "://");
    }

    @Override
    public void close() {
        this.closed.countDown();
        Connection current = this.connection;
        if (current != null) {
            try {
                current.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
