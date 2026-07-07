package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEvidenceEntity;

import java.util.UUID;

@Repository
public interface PunishmentEvidenceRepository extends PageableRepository<PunishmentEvidenceEntity, UUID> {

    Page<PunishmentEvidenceEntity> findByTenantId(UUID tenantId, Pageable pageable);

    Page<PunishmentEvidenceEntity> findByPunishmentIdentifier(String punishmentIdentifier, Pageable pageable);
}
