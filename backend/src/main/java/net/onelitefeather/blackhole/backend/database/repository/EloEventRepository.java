package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.EloEventEntity;

import java.util.UUID;

@Repository
public interface EloEventRepository extends PageableRepository<EloEventEntity, UUID> {

    Page<EloEventEntity> findByTenantIdAndOwner(UUID tenantId, String owner, Pageable pageable);
}
