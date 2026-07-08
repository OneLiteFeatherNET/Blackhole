package net.onelitefeather.blackhole.velocity.redis;

/**
 * Mirrors the backend's {@code net.onelitefeather.blackhole.backend.redis.RedisTopology}
 * byte-for-byte - the channel name and key format are a hand-shared contract between the
 * backend (the sole writer) and every Velocity proxy (a reader only).
 */
final class RedisTopology {

    static final String PUNISHMENT_SYNC_CHANNEL = "blackhole.punishment.sync";

    static String banKey(String owner) {
        return "blackhole:punish:ban:" + owner;
    }

    static String chatBanKey(String owner) {
        return "blackhole:punish:chatban:" + owner;
    }

    private RedisTopology() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
