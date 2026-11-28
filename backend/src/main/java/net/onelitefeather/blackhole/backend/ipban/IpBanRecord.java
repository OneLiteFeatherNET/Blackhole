package net.onelitefeather.blackhole.backend.ipban;

import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.util.UUID;

/**
 * One stored IP ban. A ban is active when it is not lifted and its expiry, if any, lies after {@code now}; expiry is
 * a read-time rule, so a row stays unchanged when it runs out.
 *
 * @param network     the canonical range
 * @param expiresAt   epoch milliseconds, or null for a permanent ban
 * @param liftedAt    epoch milliseconds when the ban was lifted, or null while it is not
 * @param liftedByOid the lifting actor, or null for an expiry (which is recorded as lifted)
 */
public record IpBanRecord(UUID id, IntelPrefix network, String reason, String templateKey, IpBanSource source,
                          UUID punishmentId, UUID caseId, UUID actorOid, long createdAt, Long expiresAt,
                          Long liftedAt, UUID liftedByOid, String liftReason) {

    /** @return the canonical range text */
    public String range() {
        return this.network.toString();
    }

    /** @return true when the ban holds at {@code now} */
    public boolean isActive(long now) {
        return this.liftedAt == null && (this.expiresAt == null || this.expiresAt > now);
    }
}
