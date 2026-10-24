package net.onelitefeather.blackhole.backend.events;

import java.util.List;

/** The durable consumers the backend itself runs. */
public final class EventConsumers {

    /**
     * Feeds {@code RedisSyncConsumer}, which mirrors active-punishment state into Redis so every
     * Velocity proxy sees consistent ban/mute state without querying the API on every login or chat
     * message. One consumer shared by all backend replicas: each event is processed once.
     */
    public static final EventConsumer REDIS_SYNC = new EventConsumer("blackhole-redis-sync", List.of(
            "punishment.created",
            "punishment.updated",
            "punishment.expired",
            "punishment.revoked",
            "appeal.resolved"));

    private EventConsumers() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
