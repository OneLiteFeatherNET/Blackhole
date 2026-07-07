package net.onelitefeather.blackhole.backend.imports;

import io.micronaut.serde.annotation.Serdeable;

/**
 * A single entry as found in vanilla Minecraft's {@code banned-players.json}. Field names match
 * the vanilla format exactly so it deserializes directly from the uploaded file.
 */
@Serdeable
public record VanillaBanEntry(String uuid, String name, String created, String source, String expires, String reason) {
}
