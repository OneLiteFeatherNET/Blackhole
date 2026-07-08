package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.UUID;

@Repository
public interface EloEventRepository extends PageableRepository<EloEventEntity, UUID> {

    Page<EloEventEntity> findByOwner(String owner, Pageable pageable);
}
