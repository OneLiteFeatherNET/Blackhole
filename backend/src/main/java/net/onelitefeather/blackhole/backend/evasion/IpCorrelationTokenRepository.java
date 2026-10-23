package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IpCorrelationTokenRepository extends PageableRepository<IpCorrelationTokenEntity, UUID> {

    Optional<IpCorrelationTokenEntity> findByTokenAndOwnerHash(String token, String ownerHash);

    /**
     * All owners that have used this token (IP) within the rolling detection window - more
     * than one distinct owner here is the evasion signal.
     */
    List<IpCorrelationTokenEntity> findByTokenAndLastSeenGreaterThanEquals(String token, long lastSeenAfter);

    /** Every sighting of one owner, used to find the addresses that owner shares with others. */
    List<IpCorrelationTokenEntity> findByOwnerHash(String ownerHash);

    /** The sightings of any of these tokens within the rolling detection window. */
    List<IpCorrelationTokenEntity> findByTokenInAndLastSeenGreaterThanEquals(Collection<String> tokens, long lastSeenAfter);

    /**
     * Used by the retention sweeper - this table is personal data with its own retention window.
     */
    List<IpCorrelationTokenEntity> findByLastSeenLessThan(long cutoff);
}
