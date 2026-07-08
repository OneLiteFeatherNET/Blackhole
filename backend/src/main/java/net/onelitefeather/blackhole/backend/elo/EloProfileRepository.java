package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

@Repository
public interface EloProfileRepository extends PageableRepository<EloProfileEntity, String> {
}
