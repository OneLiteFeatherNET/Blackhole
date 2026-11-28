package net.onelitefeather.blackhole.backend.intel;

import java.util.UUID;

/**
 * One login that matched a list or a hosting ASN and got a verdict other than a plain ALLOW, or an allow-listed match.
 * It holds the clear address for review, so it is kept only for {@code blackhole.intel.hit-retention}.
 *
 * @param classification every matching class, comma-separated
 * @param sourceKey      the matching sources, comma-separated
 */
public record IntelHit(UUID id, String ownerHash, String ip, String classification, String sourceKey, Long asn,
                       String organisation, IntelPolicy verdict, boolean allowListed, long createdAt) {
}
