package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.NotNull;

/**
 * A warning was given to a player, as announced by the backend: the warning's identifier and the owner's
 * hash, never the reason (the proxy reads it over HTTP).
 *
 * @param warningId the warning's identifier, also the id the player acknowledges with
 * @param owner     the hashed player the warning belongs to
 */
public record WarningMessage(@NotNull String warningId, @NotNull String owner) {
}
