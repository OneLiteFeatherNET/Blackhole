package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.TenantEntity;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends PageableRepository<TenantEntity, UUID> {

    Optional<TenantEntity> findBySlug(String slug);
}
