package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.UUID;

@Repository
public interface PunishmentRepository extends PageableRepository<PunishmentEntity, String> {

    /**
     * Whether any punishment still references the given template - checked before allowing a
     * template to be deleted, since {@link PunishmentEntity#template} is a live FK with no
     * {@code ON DELETE} cascade/set-null configured (see
     * {@code specs/002-punishment-core/tasks.md} T029).
     */
    boolean existsByTemplate_Identifier(UUID templateIdentifier);
}
