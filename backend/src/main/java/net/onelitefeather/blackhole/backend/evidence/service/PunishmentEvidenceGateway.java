package net.onelitefeather.blackhole.backend.evidence.service;

import java.util.UUID;

/**
 * What the punishment side needs from evidence, kept narrow so the punishment service depends on
 * two methods instead of the whole evidence service (and its blob store and upload pipeline).
 */
public interface PunishmentEvidenceGateway {

    /**
     * Attaches the owner's pending evidence with this correlation id to a punishment that was just
     * created.
     *
     * @return how many items were claimed
     */
    int claimPending(String owner, String correlationId, String punishmentIdentifier);

    /** The number of valid evidence items of the punishment; lifted and revoked ones are not counted. */
    long countValid(String punishmentIdentifier);

    /**
     * Marks the punishment's valid evidence {@code REVOKED} after staff revoked the punishment. The
     * evidence is kept.
     *
     * @return how many items changed
     */
    int markRevoked(String punishmentIdentifier);

    /**
     * Marks the punishment's valid evidence {@code LIFTED} after a granted full-lift appeal. The
     * evidence is kept.
     *
     * @return how many items changed
     */
    int markLifted(String punishmentIdentifier, UUID appealIdentifier);
}
