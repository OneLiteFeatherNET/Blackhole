package net.onelitefeather.blackhole.velocity.redis;

import java.util.UUID;

/**
 * Mirrors the backend's {@code net.onelitefeather.blackhole.backend.redis.PunishmentSyncMessage}
 * field-for-field - the JSON shape stored at each Redis key and published on
 * {@link RedisTopology#PUNISHMENT_SYNC_CHANNEL}. Hand-shared, not part of the OpenAPI client:
 * this is an internal side channel between the backend and proxies, not a public API contract.
 */
public record PunishmentSyncMessage(
        UUID tenantId,
        String owner,
        String slot,
        String state,
        String type,
        String punishmentIdentifier,
        String templateIdentifier,
        Long expiresAt
) {
    public static final String SLOT_BAN = "BAN";
    public static final String SLOT_CHAT_BAN = "CHAT_BAN";
    public static final String STATE_SET = "SET";
    public static final String STATE_CLEARED = "CLEARED";
}
