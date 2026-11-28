package net.onelitefeather.blackhole.backend.publicbans;

import java.util.List;
import java.util.Optional;

/**
 * Reads the listed entries of the public ban list. Every method applies the visibility rule of the list (an active entry,
 * or an ended one inside the retention window) in the query, so pages are never short and a hash outside the window
 * is simply not found.
 */
public interface PublicBanReader {

    /** A listed row as stored: the instants are raw, the service rounds them to days. */
    record ListedRow(String hash, String playerHash, String reason, String kind, long issuedDay, Long expiresAt, Long revokedAt) {
    }

    /**
     * One keyset page.
     *
     * @param after      the last entry of the previous page, or {@code null} for the first page
     * @param kind       the public kind to keep, or {@code null}
     * @param status     the status to keep, or {@code null}
     * @param playerHash the lowercase player hash to keep, or {@code null}
     * @param now        the clock, epoch millis
     * @param retentionMillis the retention window
     * @param limit      how many rows to return at most
     */
    record PageQuery(PublicBanCursor.Position after, String kind, PublicBanStatus status, String playerHash, long now,
                     long retentionMillis, int limit) {
    }

    /** The listed entries of one reason and kind, with the number that is still active. */
    record ReasonCount(String reason, String kind, long active, long total) {
    }

    List<ListedRow> page(PageQuery query);

    Optional<ListedRow> byHash(String hash, long now, long retentionMillis);

    List<ReasonCount> summary(long now, long retentionMillis);
}
