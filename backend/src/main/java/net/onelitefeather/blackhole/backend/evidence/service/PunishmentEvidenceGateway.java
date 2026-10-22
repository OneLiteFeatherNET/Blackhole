package net.onelitefeather.blackhole.backend.evidence.service;

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
}
