package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;

import java.util.UUID;

@Repository
public interface PunishmentRepository extends PageableRepository<PunishmentEntity, String> {

    Page<PunishmentEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
