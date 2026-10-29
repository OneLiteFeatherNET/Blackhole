package net.onelitefeather.blackhole.velocity.intel;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.SessionOutcome;
import net.onelitefeather.blackhole.client.model.SessionStartRequest;
import net.onelitefeather.blackhole.client.model.SessionUpdateRequest;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reports login attempts and their sessions to the backend, off the login thread. A report that cannot be
 * queued, or that the backend refuses, is dropped and logged at debug: reporting never changes a login.
 *
 * <p>The session id is created per connection and kept until the disconnect, so the end and the brand go
 * to the session the start created. Denied logins are not kept: the proxy never sees them disconnect.
 */
public final class SessionReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionReporter.class);
    /** Reports waiting behind the one being sent; a report beyond this is dropped. */
    static final int QUEUE_CAPACITY = 256;

    private final SessionBackend backend;
    private final Executor executor;
    private final Supplier<UUID> sessionIds;
    private final Map<Player, String> openSessions = new ConcurrentHashMap<>();

    /**
     * @param backend    the session calls
     * @param executor   runs the calls; it must refuse a task it cannot queue, rather than block
     * @param sessionIds creates the session id of a connection; a random UUID version 4 in production
     */
    public SessionReporter(@NotNull SessionBackend backend, @NotNull Executor executor, @NotNull Supplier<UUID> sessionIds) {
        this.backend = backend;
        this.executor = executor;
        this.sessionIds = sessionIds;
    }

    /**
     * @return a single thread with a queue of {@link #QUEUE_CAPACITY} reports; a report beyond that is refused
     */
    public static ExecutorService boundedExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
            Thread thread = new Thread(task, "blackhole-intel-report");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Reports the login attempt once, with its outcome. Call after the ban check.
     *
     * @param player  the connecting player
     * @param outcome {@link SessionOutcome#ALLOWED} or {@link SessionOutcome#DENIED}
     */
    public void reportStart(@NotNull Player player, @NotNull SessionOutcome outcome) {
        String sessionId = this.sessionIds.get().toString();
        SessionStartRequest request = new SessionStartRequest()
                .sessionId(sessionId)
                .owner(UUIDConverter.convertToSHA(player.getUniqueId()))
                .playerUuid(player.getUniqueId().toString())
                .playerName(player.getUsername())
                .ip(player.getRemoteAddress().getAddress().getHostAddress())
                .protocolVersion(player.getProtocolVersion().getProtocol())
                .virtualHost(virtualHost(player))
                .outcome(outcome);
        if (outcome == SessionOutcome.ALLOWED) {
            this.openSessions.put(player, sessionId);
        }
        if (!submit("start", () -> this.backend.record(request))) {
            this.openSessions.remove(player);
        }
    }

    /**
     * Sends the client brand of an open session. Nothing is sent for a connection without one.
     *
     * @param player the player whose client announced the brand
     * @param brand  the brand, e.g. {@code vanilla}
     */
    public void reportBrand(@NotNull Player player, @NotNull String brand) {
        String sessionId = this.openSessions.get(player);
        if (sessionId == null) {
            return;
        }
        submit("brand", () -> this.backend.update(sessionId, new SessionUpdateRequest().clientBrand(brand).ended(false)));
    }

    /**
     * Sends the end of the session of the connection, once: a second call for the same connection sends nothing.
     *
     * @param player the player who disconnected
     */
    public void reportEnd(@NotNull Player player) {
        String sessionId = this.openSessions.remove(player);
        if (sessionId == null) {
            return;
        }
        submit("end", () -> this.backend.update(sessionId, new SessionUpdateRequest().ended(true)));
    }

    /** A backend call, run by the executor; the boolean is false only if the executor refused it. */
    private boolean submit(String what, Report report) {
        try {
            this.executor.execute(() -> run(what, report));
            return true;
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Session {} report dropped, the report queue is full: {}", what, e.getMessage());
            return false;
        }
    }

    private static void run(String what, Report report) {
        try {
            report.send();
        } catch (ApiException e) {
            LOGGER.debug("Session {} report not recorded by the backend: {}", what, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.debug("Session {} report failed: {}", what, e.getMessage());
        }
    }

    private static @Nullable String virtualHost(@NotNull Player player) {
        return player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);
    }

    @FunctionalInterface
    private interface Report {
        void send() throws ApiException;
    }
}
