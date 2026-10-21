package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Wire format published on {@link RedisTopology#PUNISHMENT_SYNC_CHANNEL} and stored as the value
 * of the per-section Redis key so a proxy's login-time {@code GET} carries the same data a
 * pub/sub delta would. Hand-shared with Velocity's mirror record - deliberately not part of the
 * OpenAPI client, since this is an internal side channel between the backend and proxies, not a
 * public API contract.
 *
 * @param owner                the hashed owner
 * @param section              the key of the section the punishment belongs to
 * @param enforcement          how the section is enforced ({@code MUTE}, {@code BAN} or {@code SERVER}),
 *                             null when the section no longer exists
 * @param state                {@code SET} (a punishment is now active in this section) or
 *                             {@code CLEARED} (expired/revoked/appeal-lifted)
 * @param punishmentIdentifier the punishment this message concerns
 * @param templateIdentifier   the template it came from, null for system punishments and CLEARED messages
 * @param reason               the reason shown to the player, null for CLEARED messages
 * @param expiresAt            epoch millis the punishment expires at, or null for a permanent
 *                             punishment / a CLEARED message
 */
@Serdeable
public record PunishmentSyncMessage(
        String owner,
        String section,
        String enforcement,
        String state,
        String punishmentIdentifier,
        String templateIdentifier,
        String reason,
        Long expiresAt
) {}
