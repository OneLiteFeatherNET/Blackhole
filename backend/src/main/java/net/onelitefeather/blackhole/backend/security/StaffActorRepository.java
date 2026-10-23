package net.onelitefeather.blackhole.backend.security;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.UUID;

@Repository
public interface StaffActorRepository extends CrudRepository<StaffActorEntity, UUID> {
}
