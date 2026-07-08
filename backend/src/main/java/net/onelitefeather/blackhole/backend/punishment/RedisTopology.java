package net.onelitefeather.blackhole.backend.punishment;

/**
 * Well-known Redis channel/key names shared between {@link PunishmentRedisWriter} and every
 * Velocity proxy's read-side mirror - the key/channel format here must be replicated
 * byte-for-byte on the Velocity side.
 */
public final class RedisTopology {

    /** Pub/Sub channel carrying every {@link PunishmentSyncMessage} delta. */
    public static final String PUNISHMENT_SYNC_CHANNEL = "blackhole.punishment.sync";

    public static String banKey(String owner) {
        return "blackhole:punish:ban:" + owner;
    }

    public static String chatBanKey(String owner) {
        return "blackhole:punish:chatban:" + owner;
    }

    private RedisTopology() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
