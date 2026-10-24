package net.onelitefeather.blackhole.backend.evidence.service;

import net.onelitefeather.blackhole.backend.security.Attribution;

import java.util.Collection;
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

    /**
     * Attaches the owner's pending evidence items with these ids (the evidence a report references) to a
     * punishment that was just created. Items that are not pending or belong to someone else are skipped.
     *
     * @return how many items were claimed
     */
    int claimPendingByIds(String owner, Collection<UUID> evidenceIds, String punishmentIdentifier);

    /**
     * The team evidence number: valid items with staff provenance; lifted and revoked ones, the protocol and files the appealing player
     * uploaded themselves are not counted.
     */
    long countValid(String punishmentIdentifier);

    /** The valid files the appealing player sent in the chat, reported next to (never inside) {@link #countValid}. */
    default long countPlayerSupplied(String punishmentIdentifier) {
        return 0;
    }

    /**
     * Marks the punishment's valid evidence {@code REVOKED} after staff revoked the punishment. The
     * evidence is kept.
     *
     * @param by who revoked the punishment, for the case timeline
     * @return how many items changed
     */
    int markRevoked(String punishmentIdentifier, Attribution by);

    /**
     * Marks the punishment's valid evidence {@code LIFTED} after a granted full-lift appeal. The
     * evidence is kept.
     *
     * @param by the reviewer who granted the appeal, for the case timeline
     * @return how many items changed
     */
    int markLifted(String punishmentIdentifier, UUID appealIdentifier, Attribution by);

    /**
     * Deletes the punishment's evidence rows and files; to be called before the punishment record is
     * deleted. A file that cannot be deleted is left for the housekeeping sweep.
     *
     * @return how many items were deleted
     */
    int deleteForPunishment(String punishmentIdentifier);
}
