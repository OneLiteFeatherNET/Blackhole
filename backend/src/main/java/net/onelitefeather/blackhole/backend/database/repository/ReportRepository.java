package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.ReportEntity;

import java.util.UUID;

@Repository
public interface ReportRepository extends PageableRepository<ReportEntity, UUID> {

    Page<ReportEntity> findByTenantId(UUID tenantId, Pageable pageable);

    long countByTenantIdAndReporterHashAndCreatedAtGreaterThan(UUID tenantId, String reporterHash, long createdAtAfter);

    /**
     * Aggregate, tenant-wide submission count within the window - a backstop independent of
     * {@code reporterHash}. That field is client-supplied (no JWT in this system carries a
     * per-player identity, only a role), so a caller could otherwise defeat the per-reporter
     * limit by simply varying it on every request.
     */
    long countByTenantIdAndCreatedAtGreaterThan(UUID tenantId, long createdAtAfter);
}
