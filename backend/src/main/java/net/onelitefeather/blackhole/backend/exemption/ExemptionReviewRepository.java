package net.onelitefeather.blackhole.backend.exemption;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExemptionReviewRepository extends CrudRepository<ExemptionReviewEntity, UUID> {

    /** The reviews in one state, for example the open queue. */
    List<ExemptionReviewEntity> findByState(String state);
}
