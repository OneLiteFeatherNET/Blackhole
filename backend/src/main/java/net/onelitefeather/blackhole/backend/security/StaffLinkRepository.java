package net.onelitefeather.blackhole.backend.security;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffLinkRepository extends CrudRepository<StaffLinkEntity, UUID> {

    Optional<StaffLinkEntity> findByMinecraftUuid(UUID minecraftUuid);
}
