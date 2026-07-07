package net.onelitefeather.blackhole.backend.imports;

import io.micronaut.serde.annotation.Serdeable;

/**
 * A single entry as found in vanilla Minecraft's {@code banned-ips.json}. Blackhole has no
 * first-class IP-ban concept, so these are only counted/summarized, never imported.
 */
@Serdeable
public record VanillaIpBanEntry(String ip, String created, String source, String expires, String reason) {
}
