package net.onelitefeather.blackhole.backend.playerresolver;

import java.util.UUID;

/**
 * @param source which {@link PlayerResolver} produced this result, e.g. {@code "otis"} - kept in
 *               the DTO response so callers/logs can tell where a UUID came from.
 */
public record ResolvedPlayer(UUID uuid, String name, String source) {
}
