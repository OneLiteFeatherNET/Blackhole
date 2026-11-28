package net.onelitefeather.blackhole.backend.intel.names;

import java.util.UUID;

/** An owner that used a name: its raw UUID, its newest name, and first and last seen of the matched name. */
public record OwnerRecord(String ownerHash, UUID playerUuid, String currentName, long firstSeen, long lastSeen) {
}
