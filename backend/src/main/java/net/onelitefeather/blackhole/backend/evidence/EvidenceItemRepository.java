package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvidenceItemRepository extends PageableRepository<EvidenceItemEntity, UUID> {

    /** Every item of a punishment, oldest first, whatever its status. */
    Page<EvidenceItemEntity> findByPunishmentIdentifierOrderByCreatedAt(String punishmentIdentifier, Pageable pageable);

    /** The items of a punishment with the given status, oldest first. */
    Page<EvidenceItemEntity> findByPunishmentIdentifierAndStatusOrderByCreatedAt(
            String punishmentIdentifier, EvidenceStatus status, Pageable pageable);

    /**
     * How many items of the punishment have the status and are not of the given kind; the player-visible
     * evidence count passes {@link EvidenceKind#TRANSCRIPT}, which is not evidence a player is told about.
     */
    long countByPunishmentIdentifierAndStatusAndKindNotEqual(String punishmentIdentifier, EvidenceStatus status, EvidenceKind kind);

    /** All items of a punishment, for lifecycle changes and cascades. */
    List<EvidenceItemEntity> findByPunishmentIdentifier(String punishmentIdentifier);

    /** Every item of an owner (access and erasure requests), oldest first. */
    Page<EvidenceItemEntity> findByOwnerOrderByCreatedAt(String owner, Pageable pageable);

    /** The valid items of an owner, oldest first. */
    Page<EvidenceItemEntity> findByOwnerAndStatusOrderByCreatedAt(String owner, EvidenceStatus status, Pageable pageable);

    /** All items of an owner, for erasure. */
    List<EvidenceItemEntity> findByOwner(String owner);

    /** The pending items a new punishment of the owner with this correlation id claims. */
    List<EvidenceItemEntity> findByOwnerAndCorrelationIdAndPunishmentIdentifierIsNull(String owner, String correlationId);

    /** Pending items created before the cutoff, for the unclaimed-expiry sweep. */
    List<EvidenceItemEntity> findByPunishmentIdentifierIsNullAndCreatedAtLessThan(long createdBefore);

    /** Whether a row references the blob; the reconcile sweep deletes blobs nobody references. */
    boolean existsByFileKey(String fileKey);

    /** Whether a transcript row references the blob as its JSON file. */
    boolean existsByJsonFileKey(String jsonFileKey);
}
