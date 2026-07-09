package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Wire format published on {@link RedisTopology#PUNISHMENT_SYNC_CHANNEL} and stored as the value
 * of the per-profile Redis key so a proxy's login-time {@code GET} carries the same data a
 * pub/sub delta would. Hand-shared with Velocity's mirror record - deliberately not part of the
 * OpenAPI client, since this is an internal side channel between the backend and proxies, not a
 * public API contract.
 *
 * @param slot                  {@code BAN} or {@code CHAT_BAN} - which of
 *                              {@code PunishmentProfileEntity}'s two active-punishment fields
 *                              this message concerns
 * @param state                 {@code SET} (a punishment is now active in this slot) or
 *                              {@code CLEARED} (expired/revoked/appeal-lifted)
 * @param type                  the {@code PunishType} the active punishment was applied as
 *                              (SERVER/NETWORK/CHAT), null when {@code state == CLEARED} and the
 *                              type isn't known
 * @param expiresAt             epoch millis the punishment expires at, or null for a permanent
 *                              punishment / a CLEARED message
 */
@Serdeable
public record PunishmentSyncMessage(
        String owner,
        String slot,
        String state,
        String type,
        String punishmentIdentifier,
        String templateIdentifier,
        Long expiresAt
) {}
