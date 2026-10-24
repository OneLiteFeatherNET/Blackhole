package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.EventTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * An in-memory {@link EventTransport}: records every published envelope and completes (or fails)
 * the returned stage as the test decides, so no broker is needed.
 */
public class RecordingEventTransport implements EventTransport {

    /** One published envelope with the event type it was published under. */
    public record Published(String eventType, DomainEvent event) {
    }

    private final List<Published> published = new ArrayList<>();
    private CompletionStage<Void> result = CompletableFuture.completedFuture(null);
    private RuntimeException failure;
    private final Consumer<Published> listener;

    public RecordingEventTransport() {
        this(published -> { });
    }

    /** @param listener also told about every published envelope */
    public RecordingEventTransport(Consumer<Published> listener) {
        this.listener = listener;
    }

    /** Makes the next publishes return this stage instead of an already completed one. */
    public void respondWith(CompletionStage<Void> stage) {
        this.result = stage;
    }

    /** Makes the next publishes throw this exception synchronously. */
    public void throwOnPublish(RuntimeException exception) {
        this.failure = exception;
    }

    @Override
    public CompletionStage<Void> publish(String eventType, DomainEvent event) {
        if (this.failure != null) {
            throw this.failure;
        }
        Published entry = new Published(eventType, event);
        this.published.add(entry);
        this.listener.accept(entry);
        return this.result;
    }

    public List<Published> published() {
        return this.published;
    }
}
