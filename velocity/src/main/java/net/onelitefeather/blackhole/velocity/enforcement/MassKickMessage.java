package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.NotNull;

/**
 * A {@code kick.mass} event: the backend asks every proxy to take everybody off a server or section.
 *
 * @param actionId the mass kick's identifier, to read the reason and to acknowledge
 * @param scope    {@code SERVER} or {@code SECTION}
 * @param target   the server name or section key
 */
public record MassKickMessage(@NotNull String actionId, @NotNull String scope, @NotNull String target) {
}
