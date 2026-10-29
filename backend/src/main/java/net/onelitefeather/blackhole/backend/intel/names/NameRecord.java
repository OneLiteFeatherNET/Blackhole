package net.onelitefeather.blackhole.backend.intel.names;

import java.util.UUID;

/** One name of one owner. {@code name} is the newest casing; {@code playerUuid} is the raw UUID (add-player-intelligence decision 10). */
public record NameRecord(String ownerHash, UUID playerUuid, String name, long firstSeen, long lastSeen) {
}
