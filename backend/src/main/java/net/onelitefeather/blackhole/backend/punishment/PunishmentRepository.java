package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PunishmentRepository extends PageableRepository<PunishmentEntity, String> {

    /**
     * Every punishment with the given short ID key (eight upper case hex characters). Not unique:
     * short IDs are four bytes, so a lookup can match several punishments.
     */
    List<PunishmentEntity> findByShortId(String shortId);

    /**
     * Whether any punishment still references the given template - checked before allowing a
     * template to be deleted, since {@link PunishmentEntity#getTemplate()} is a live FK with no
     * {@code ON DELETE} cascade/set-null configured.
     */
    boolean existsByTemplate_Identifier(UUID templateIdentifier);

    /**
     * How many of the owner's punishments in an escalation group were created at or after
     * {@code createdSince} and were not lifted (revoked, or lifted by a granted appeal). Expired
     * punishments count. This is the {@code n} of escalation step selection.
     */
    long countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
            String owner, String escalationGroup, long createdSince
    );
}
