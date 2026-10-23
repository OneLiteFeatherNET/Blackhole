package net.onelitefeather.blackhole.backend.security;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffLinkCodeRepository extends CrudRepository<StaffLinkCodeEntity, UUID> {

    Optional<StaffLinkCodeEntity> findByCodeHash(String codeHash);

    /** Removes every code that expired at or before {@code now}. */
    void deleteByExpiresAtLessThanEquals(long now);
}
