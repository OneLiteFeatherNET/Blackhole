package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;

@Repository
public interface EloScoreRepository extends PageableRepository<EloScoreEntity, EloScoreId> {

    /**
     * Every recorded score of one owner.
     */
    List<EloScoreEntity> findByIdOwner(String owner);
}
