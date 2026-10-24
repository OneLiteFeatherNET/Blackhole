package net.onelitefeather.blackhole.backend.events;

/** One running durable consumer; closing it stops delivery. */
public interface EventSubscription extends AutoCloseable {

    @Override
    void close();
}
