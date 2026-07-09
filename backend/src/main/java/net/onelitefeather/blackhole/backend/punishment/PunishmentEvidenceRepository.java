package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.UUID;

@Repository
public interface PunishmentEvidenceRepository extends PageableRepository<PunishmentEvidenceEntity, UUID> {

    Page<PunishmentEvidenceEntity> findByPunishmentIdentifier(String punishmentIdentifier, Pageable pageable);
}
