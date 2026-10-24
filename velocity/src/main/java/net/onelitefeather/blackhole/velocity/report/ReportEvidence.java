package net.onelitefeather.blackhole.velocity.report;

import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshot;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
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
 * Sends the reported player's recent chat to the backend as {@code CHAT_LOG} evidence, through the same
 * snapshot builder and redaction as punishment evidence, and returns the evidence id the report then
 * references. Like punishment evidence it never blocks or fails the report: the upload is bounded by the
 * configured timeout and any failure only yields {@link Result.Status#FAILED}. Neither the chat nor the
 * reason of a failure is logged.
 */
public final class ReportEvidence {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportEvidence.class);

    /**
     * What happened to the evidence of one report.
     *
     * @param status     whether evidence was attached
     * @param evidenceId the id of the stored evidence, only for {@link Status#ATTACHED}
     * @param lines      how many lines were attached
     */
    public record Result(@NotNull Status status, @Nullable UUID evidenceId, int lines) {

        /** How the attempt ended. */
        public enum Status {
            /** Capture is off or the player had no buffered chat; nothing was sent. */
            NONE,
            /** The evidence is stored. */
            ATTACHED,
            /** The evidence could not be stored; the report goes ahead without it. */
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

    public ReportEvidence(
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

    /** Evidence with a random UUID as correlation id. */
    public static @NotNull ReportEvidence create(
            @NotNull ChatSnapshotBuilder snapshots, @NotNull BackendGateway backend, @NotNull ChatCaptureConfig config
    ) {
        return new ReportEvidence(snapshots, backend, config, () -> UUID.randomUUID().toString());
    }

    /**
     * Uploads the reported player's buffered chat, if capture is on and there is any.
     *
     * @param reported the owner hash of the reported player
     */
    public @NotNull Result attach(@NotNull String reported) {
        if (!this.enabled) {
            return Result.NOTHING;
        }
        Optional<ChatSnapshot> snapshot = this.snapshots.build(reported);
        if (snapshot.isEmpty()) {
            return Result.NOTHING;
        }
        try {
            UUID id = this.backend.postReportChatEvidence(reported, this.correlationIds.get(), snapshot.get(), this.sourceSystem)
                    .get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (id != null) {
                return new Result(Result.Status.ATTACHED, id, snapshot.get().size());
            }
        } catch (TimeoutException e) {
            LOGGER.warn("Report evidence was not stored in time, reporting without it");
        } catch (ExecutionException e) {
            LOGGER.warn("Report evidence was refused, reporting without it ({})", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Report evidence upload was interrupted, reporting without it");
        } catch (RuntimeException e) {
            LOGGER.warn("Report evidence could not be sent, reporting without it ({})", e.getClass().getSimpleName());
        }
        return new Result(Result.Status.FAILED, null, 0);
    }
}
