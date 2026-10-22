package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Sends the punished player's buffered chat to the backend as {@code CHAT_LOG} evidence under a
 * fresh correlation id, which the punishment call then carries to claim it. This never blocks or
 * fails a punishment: the upload is asynchronous and waited for at most the configured timeout, and
 * any failure only yields {@link Result.Status#FAILED} for the staff member to be warned about.
 * Neither the chat nor the reason of a failure is logged.
 */
public final class ChatEvidence {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatEvidence.class);

    /**
     * What happened to the evidence of one punishment.
     *
     * @param status        whether evidence was attached
     * @param correlationId the id the punishment must carry, only for {@link Status#ATTACHED}
     * @param lines         how many lines were attached
     */
    public record Result(@NotNull Status status, @Nullable String correlationId, int lines) {

        /** How the attempt ended. */
        public enum Status {
            /** Capture is off or the player had no buffered chat; nothing was sent. */
            NONE,
            /** The evidence is stored and waits to be claimed by the punishment. */
            ATTACHED,
            /** The evidence could not be stored; the punishment goes ahead without it. */
            FAILED
        }

        static final Result NOTHING = new Result(Status.NONE, null, 0);
    }

    private final ChatSnapshotBuilder snapshots;
    private final BackendGateway backend;
    private final boolean enabled;
    private final String sourceSystem;
    private final Duration timeout;
    private final Supplier<String> correlationIds;

    public ChatEvidence(
            @NotNull ChatSnapshotBuilder snapshots, @NotNull BackendGateway backend, @NotNull ChatCaptureConfig config,
            @NotNull Supplier<String> correlationIds
    ) {
        this.snapshots = snapshots;
        this.backend = backend;
        this.enabled = config.isEnabled();
        this.sourceSystem = config.getSourceSystem();
        this.timeout = config.getTimeout();
        this.correlationIds = correlationIds;
    }

    /**
     * Evidence with a random UUID as correlation id.
     */
    public static @NotNull ChatEvidence create(
            @NotNull ChatSnapshotBuilder snapshots, @NotNull BackendGateway backend, @NotNull ChatCaptureConfig config
    ) {
        return new ChatEvidence(snapshots, backend, config, () -> UUID.randomUUID().toString());
    }

    /**
     * Uploads the target's buffered chat, if capture is on and there is any.
     *
     * @param target the owner hash of the player about to be punished
     * @param staff  the staff member punishing them
     */
    public @NotNull Result attach(@NotNull String target, @NotNull UUID staff) {
        if (!this.enabled) {
            return Result.NOTHING;
        }
        Optional<ChatSnapshot> snapshot = this.snapshots.build(target);
        if (snapshot.isEmpty()) {
            return Result.NOTHING;
        }
        String correlationId = this.correlationIds.get();
        try {
            this.backend.postChatEvidence(target, correlationId, snapshot.get(), this.sourceSystem, staff)
                    .get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(Result.Status.ATTACHED, correlationId, snapshot.get().size());
        } catch (TimeoutException e) {
            LOGGER.warn("Chat evidence was not stored in time, punishing without it");
        } catch (ExecutionException e) {
            LOGGER.warn("Chat evidence was refused, punishing without it ({})", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Chat evidence upload was interrupted, punishing without it");
        } catch (RuntimeException e) {
            LOGGER.warn("Chat evidence could not be sent, punishing without it ({})", e.getClass().getSimpleName());
        }
        return new Result(Result.Status.FAILED, null, 0);
    }
}
