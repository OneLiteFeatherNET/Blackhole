package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.IpCorrelationTokenEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IpCorrelationTokenRepository extends PageableRepository<IpCorrelationTokenEntity, UUID> {

    Optional<IpCorrelationTokenEntity> findByTenantIdAndTokenAndOwnerHash(UUID tenantId, String token, String ownerHash);

    /**
     * All owners that have used this token (IP) within the rolling detection window - more
     * than one distinct owner here is the evasion signal.
     */
    List<IpCorrelationTokenEntity> findByTenantIdAndTokenAndLastSeenGreaterThanEquals(UUID tenantId, String token, long lastSeenAfter);

    /**
     * Used by the retention sweeper - this table is personal data with its own retention window.
     */
    List<IpCorrelationTokenEntity> findByLastSeenLessThan(long cutoff);
}
