package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.KickAccepted;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.client.model.KickStatus;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Sends a kick and follows it to its outcome without ever blocking the calling (server) thread. The
 * backend calls run on the executor; between two reads the runner waits on a scheduler delay instead of
 * sleeping, so no thread is held. Reading stops as soon as the status is no longer {@code PENDING}, or
 * when the local deadline passes - the backend derives the final status from its own deadline, so the
 * local one only guards against a status that never settles.
 */
public final class KickRunner {

    /** Runs a task once after a delay, without blocking. */
    @FunctionalInterface
    public interface Delay {
        void after(@NotNull Duration delay, @NotNull Runnable task);
    }

    /** What happened to the kick; called on whatever thread the step ran on. */
    public interface Listener {
        /** The backend took the kick; nothing is delivered yet. */
        void accepted(@NotNull KickAccepted accepted);

        /** The kick has a final status. */
        void settled(@NotNull KickResponse response);

        /** The local deadline passed while the kick was still pending. */
        void timedOut(@NotNull String id);

        /** The backend refused the kick or could not be reached. */
        void failed(@NotNull ApiException cause);
    }

    private final BackendGateway backend;
    private final Executor async;
    private final Delay delay;
    private final Clock clock;
    private final Supplier<String> keys;
    private final Duration pollEvery;
    private final Duration giveUpAfter;

    /**
     * @param async       where backend calls run, never the server thread
     * @param keys        the source of idempotency keys, one per kick
     * @param pollEvery   how long to wait between two status reads
     * @param giveUpAfter how long after the backend took the kick to stop reading
     */
    public KickRunner(
            @NotNull BackendGateway backend, @NotNull Executor async, @NotNull Delay delay, @NotNull Clock clock,
            @NotNull Supplier<String> keys, @NotNull Duration pollEvery, @NotNull Duration giveUpAfter
    ) {
        this.backend = backend;
        this.async = async;
        this.delay = delay;
        this.clock = clock;
        this.keys = keys;
        this.pollEvery = pollEvery;
        this.giveUpAfter = giveUpAfter;
    }

    /**
     * Kicks one player.
     *
     * @param target the server name or section key, null for {@code NETWORK}
     */
    public void kickPlayer(@NotNull String owner, @NotNull KickScope scope, @Nullable String target, @NotNull String reason, @NotNull Listener listener) {
        String key = this.keys.get();
        this.async.execute(() -> {
            try {
                follow(this.backend.kick(key, owner, scope, target, reason), listener);
            } catch (ApiException e) {
                listener.failed(e);
            } catch (RuntimeException e) {
                listener.failed(new ApiException(e));
            }
        });
    }

    /** Kicks everybody from a server or section. */
    public void kickMass(@NotNull KickScope scope, @NotNull String target, @NotNull String reason, @NotNull Listener listener) {
        String key = this.keys.get();
        this.async.execute(() -> {
            try {
                follow(this.backend.kickMass(key, scope, target, reason), listener);
            } catch (ApiException e) {
                listener.failed(e);
            } catch (RuntimeException e) {
                listener.failed(new ApiException(e));
            }
        });
    }

    private void follow(KickAccepted accepted, Listener listener) {
        listener.accepted(accepted);
        schedulePoll(accepted.getId(), this.clock.millis() + this.giveUpAfter.toMillis(), listener);
    }

    private void schedulePoll(String id, long giveUpAtMillis, Listener listener) {
        this.delay.after(this.pollEvery, () -> this.async.execute(() -> {
            try {
                KickResponse response = this.backend.kickStatus(id);
                if (response.getStatus() != KickStatus.PENDING) {
                    listener.settled(response);
                } else if (this.clock.millis() >= giveUpAtMillis) {
                    listener.timedOut(id);
                } else {
                    schedulePoll(id, giveUpAtMillis, listener);
                }
            } catch (ApiException e) {
                listener.failed(e);
            } catch (RuntimeException e) {
                listener.failed(new ApiException(e));
            }
        }));
    }
}
