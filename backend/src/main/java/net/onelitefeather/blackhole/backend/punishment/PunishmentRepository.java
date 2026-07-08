package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

@Repository
public interface PunishmentRepository extends PageableRepository<PunishmentEntity, String> {
}
