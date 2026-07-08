package net.onelitefeather.blackhole.backend.report;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.UUID;

@Repository
public interface ReportRepository extends PageableRepository<ReportEntity, UUID> {

    long countByReporterHashAndCreatedAtGreaterThan(String reporterHash, long createdAtAfter);

    /**
     * Aggregate, network-wide submission count within the window - a backstop independent of
     * {@code reporterHash}. That field is client-supplied (no JWT in this system carries a
     * per-player identity, only a role), so a caller could otherwise defeat the per-reporter
     * limit by simply varying it on every request.
     */
    long countByCreatedAtGreaterThan(long createdAtAfter);
}
