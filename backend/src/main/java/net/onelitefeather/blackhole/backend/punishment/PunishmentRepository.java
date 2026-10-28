package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Pageable;
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

    /** The punishments of one source (such as the vanilla import identity) that are linked to no template. */
    List<PunishmentEntity> findBySourceAndTemplateIsNull(UUID source);

    /**
     * How many of the owner's punishments in an escalation group were created at or after
     * {@code createdSince} and were not lifted (revoked, or lifted by a granted appeal). Expired
     * punishments count. This is the {@code n} of escalation step selection.
     */
    long countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
            String owner, String escalationGroup, long createdSince
    );

    /**
     * The same count restricted to warnings. Escalation counts a warning only inside the template's warn window
     * (or the escalation window when it sets none), so the caller subtracts this from the total and adds it back for
     * the warn window.
     */
    @Query("SELECT COUNT(p) FROM PunishmentEntity p WHERE p.owner = :owner AND p.escalationGroup = :escalationGroup "
            + "AND p.lifted = false AND p.createdAt >= :createdSince AND p.kind = net.onelitefeather.blackhole.backend.punishment.PunishmentKind.WARN")
    long countWarningsByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
            String owner, String escalationGroup, long createdSince
    );

    /**
     * The owner's warnings nobody acknowledged yet, oldest first, at most the page size. The proxy reads them on login;
     * a player with more pending warnings than one page sees the rest on the next login.
     */
    @Query("SELECT p FROM PunishmentEntity p WHERE p.owner = :owner "
            + "AND p.kind = net.onelitefeather.blackhole.backend.punishment.PunishmentKind.WARN "
            + "AND p.acknowledgedAt IS NULL ORDER BY p.createdAt ASC")
    List<PunishmentEntity> findPendingWarnings(String owner, Pageable pageable);
}
