package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Collects concurrent scoring requests into one batch. A batch goes out when it reaches {@code maxSize} or
 * when {@code linger} has passed since its first message, whichever comes first. The batcher holds no state
 * beyond the pending batch.
 */
public final class MicroBatcher {

    private final int maxSize;
    private final Duration linger;
    private final TaskScheduler scheduler;
    private final Function<List<String>, CompletableFuture<List<Double>>> sender;

    private final List<String> texts = new ArrayList<>();
    private final List<CompletableFuture<Double>> waiters = new ArrayList<>();
    private TaskScheduler.Cancellable lingerTimer;

    public MicroBatcher(int maxSize, Duration linger, TaskScheduler scheduler,
                        Function<List<String>, CompletableFuture<List<Double>>> sender) {
        this.maxSize = maxSize;
        this.linger = linger;
        this.scheduler = scheduler;
        this.sender = sender;
    }

    public CompletableFuture<Double> submit(String text) {
        CompletableFuture<Double> waiter = new CompletableFuture<>();
        Batch full = null;
        synchronized (this) {
            texts.add(text);
            waiters.add(waiter);
            if (texts.size() >= maxSize) {
                full = drain();
            } else if (lingerTimer == null) {
                lingerTimer = scheduler.schedule(linger, this::flush);
            }
        }
        if (full != null) {
            dispatch(full);
        }
        return waiter;
    }

    private void flush() {
        Batch pending;
        synchronized (this) {
            pending = drain();
        }
        dispatch(pending);
    }

    /** Must be called while holding the lock. */
    private Batch drain() {
        if (lingerTimer != null) {
            lingerTimer.cancel();
            lingerTimer = null;
        }
        Batch batch = new Batch(List.copyOf(texts), List.copyOf(waiters));
        texts.clear();
        waiters.clear();
        return batch;
    }

    private void dispatch(Batch batch) {
        if (batch.texts().isEmpty()) {
            return;
        }
        CompletableFuture<List<Double>> call;
        try {
            call = sender.apply(batch.texts());
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((scores, error) -> {
            for (int i = 0; i < batch.waiters().size(); i++) {
                boolean scored = error == null && scores != null && i < scores.size();
                batch.waiters().get(i).complete(scored ? scores.get(i) : 0.0);
            }
        });
    }

    private record Batch(List<String> texts, List<CompletableFuture<Double>> waiters) {
    }
}
