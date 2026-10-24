package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;

/** Replaces the broker publisher by a recording one, only when a test switches {@code test.recording-events} on. */
@Singleton
@Replaces(DomainEventPublisher.class)
@Requires(property = "test.recording-events", value = "true")
class RecordingPublisherBean extends RecordingEventPublisher {
}
