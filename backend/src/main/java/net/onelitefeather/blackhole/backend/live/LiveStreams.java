package net.onelitefeather.blackhole.backend.live;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.sse.Event;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Opens a live stream for an HTTP caller: the hub's frames become a publisher of SSE events. The sink is created before the stream is
 * opened, so the first frames wait in its buffer until the response subscribes. Closing the response closes the stream.
 */
@Singleton
public class LiveStreams {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveStreams.class);

    /** What an opening answered. The controller maps each variant to a status. */
    public sealed interface Opening {

        /** The stream is open; {@code events} is the body to send as {@code text/event-stream}. */
        record Opened(Publisher<Event<String>> events) implements Opening {
        }

        /** The replica is full or the stream could not be opened: 503, the client polls. */
        record Busy() implements Opening {
        }

        /** The case is unknown: 404. */
        record NotFound() implements Opening {
        }

        /** The caller may not listen to this case (a leaked Ban ID, a proxy): 403. */
        record Forbidden() implements Opening {
        }
    }

    private final LiveHub hub;

    public LiveStreams(LiveHub hub) {
        this.hub = hub;
    }

    /**
     * @param lastEventId the {@code Last-Event-ID} header, or {@code null}
     */
    public Opening open(LiveHub.Subscription subscription, @Nullable String lastEventId) {
        Sinks.Many<LiveFrame> sink = Sinks.many().unicast().onBackpressureBuffer();
        LiveHub.Stream stream;
        try {
            stream = this.hub.open(subscription, lastEventId, frame -> sink.tryEmitNext(frame), () -> sink.tryEmitComplete());
        } catch (LiveHub.CapacityExceeded full) {
            return new Opening.Busy();
        } catch (RuntimeException failed) {
            LOGGER.warn("A live stream could not be opened; the client polls", failed);
            return new Opening.Busy();
        }
        Flux<Event<String>> events = sink.asFlux().doOnCancel(stream::close).map(LiveEvents::toEvent);
        return new Opening.Opened(events);
    }
}
