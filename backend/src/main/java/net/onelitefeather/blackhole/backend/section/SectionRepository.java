package net.onelitefeather.blackhole.backend.section;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

@Repository
public interface SectionRepository extends PageableRepository<SectionEntity, String> {
}
