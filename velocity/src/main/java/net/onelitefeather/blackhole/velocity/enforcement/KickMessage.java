package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code punishment.kick} event: the backend asks every proxy to take one player off the network, a
 * server or a section. Like {@link PunishmentSyncMessage} it is the proxy's own representation of the
 * backend's payload; the payload never carries the reason, the proxy reads it with {@code GET /kick/{id}}.
 *
 * @param kickId the kick's identifier, to read the reason and to acknowledge
 * @param owner  the hashed player
 * @param scope  {@code NETWORK}, {@code SERVER} or {@code SECTION}
 * @param target the server name or section key, null for a network kick
 */
public record KickMessage(@NotNull String kickId, @NotNull String owner, @NotNull String scope, @Nullable String target) {
}
