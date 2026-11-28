package net.onelitefeather.blackhole.backend.areaban;

import java.util.UUID;

/**
 * One area ban: an autonomous system or a country. Expired and lifted bans stay as history. {@code expiresAt} and
 * {@code liftedAt} are epoch milliseconds, null when absent.
 */
public record AreaBan(
        UUID id,
        Kind kind,
        String value,
        String reason,
        Source source,
        String actorOid,
        long createdAt,
        Long expiresAt,
        Long liftedAt,
        String liftReason,
        String liftedByOid
) {

    /** ASN or ISO 3166 alpha-2 country. */
    public enum Kind {
        ASN,
        COUNTRY
    }

    /** Who created it. */
    public enum Source {
        MANUAL,
        AUTO_BURST
    }

    /** Whether the ban applies at {@code nowMillis}: not lifted, and not past its expiry. */
    public boolean isActiveAt(long nowMillis) {
        return this.liftedAt == null && (this.expiresAt == null || this.expiresAt > nowMillis);
    }

    /** A copy of this ban, marked as lifted. */
    public AreaBan lifted(long at, String reason, String byOid) {
        return new AreaBan(this.id, this.kind, this.value, this.reason, this.source, this.actorOid, this.createdAt,
                this.expiresAt, at, reason, byOid);
    }
}
