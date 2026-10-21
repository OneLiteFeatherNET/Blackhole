package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvidenceAuditRepository extends PageableRepository<EvidenceAuditEntity, UUID> {

    /** The audit trail of one evidence item, oldest first. */
    List<EvidenceAuditEntity> findByEvidenceIdentifierOrderByOccurredAt(UUID evidenceIdentifier);

    /** Entries older than the cutoff; only purged when the operator configures an audit retention. */
    List<EvidenceAuditEntity> findByOccurredAtLessThan(long cutoff);
}
