package net.onelitefeather.blackhole.backend.intel;

import java.util.UUID;

/**
 * One exemption: the owner is ALLOW for every class until {@code expiresAt}. A null expiry is permanent. Keyed by the
 * owner hash only, so no name and no address is stored with it.
 */
public record IntelAllowEntry(String ownerHash, String reason, UUID actorOid, Long expiresAt, long createdAt) {
}
