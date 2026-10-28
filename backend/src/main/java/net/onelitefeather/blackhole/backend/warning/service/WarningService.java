package net.onelitefeather.blackhole.backend.warning.service;

import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;
import net.onelitefeather.blackhole.backend.warning.dto.WarningDTO;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads warnings and records the player's confirmation of them. A warning is a punishment row of kind {@code WARN}:
 * it is never active and is never mirrored to the proxies. Every method answers with the response or the
 * {@link WarningDTO.Error} variant; none throws for an expected failure.
 */
@Singleton
public class WarningService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final PunishmentRepository punishments;
    private final CaseTimeline caseTimeline;
    private final Clock clock;
    private final StaffLinkService staffLinks;

    public WarningService(PunishmentRepository punishments, CaseTimeline caseTimeline, Clock clock, StaffLinkService staffLinks) {
        this.punishments = punishments;
        this.caseTimeline = caseTimeline;
        this.clock = clock;
        this.staffLinks = staffLinks;
    }

    /**
     * Reads one warning.
     *
     * @return the {@link WarningDTO.Response}, or a {@link WarningDTO.Error} when there is no such warning
     */
    public WarningDTO find(String id) {
        return warning(id).<WarningDTO>map(this::response).orElseGet(WarningService::notFound);
    }

    /**
     * Reads the owner's warnings the player has not confirmed yet, oldest first.
     *
     * @param limit the most warnings to answer; {@link #DEFAULT_LIMIT} when absent, at most {@link #MAX_LIMIT}
     * @return the {@link WarningDTO.Pending}, or a {@link WarningDTO.Error} for a malformed owner or limit
     */
    public WarningDTO pending(String owner, Integer limit) {
        if (!OwnerHash.isValid(owner)) {
            return new WarningDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            return new WarningDTO.Error("limit must be between 1 and " + MAX_LIMIT, ErrorKind.VALIDATION);
        }
        List<WarningDTO.Response> items = this.punishments.findPendingWarnings(owner, Pageable.from(0, size)).stream()
                .map(this::response)
                .toList();
        return new WarningDTO.Pending(items);
    }

    /**
     * Records the player's confirmation. The first one sets the time and is recorded on the case; a repeat keeps the
     * first time and records nothing. A warning that belongs to another owner is reported as unknown, so the answer
     * reveals nothing.
     *
     * @return the {@link WarningDTO.Acknowledged}, or a {@link WarningDTO.Error}; in every error case nothing changed
     */
    public WarningDTO acknowledge(String id, WarningDTO.AcknowledgeRequest request) {
        if (!OwnerHash.isValid(request.owner())) {
            return new WarningDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        Optional<PunishmentEntity> warning = warning(id).filter(found -> request.owner().equals(found.getOwner()));
        if (warning.isEmpty()) {
            return notFound();
        }
        PunishmentEntity pending = warning.get();
        if (!pending.acknowledge(this.clock.millis())) {
            return new WarningDTO.Acknowledged(pending.getAcknowledgedAt());
        }
        PunishmentEntity saved = this.punishments.save(pending);
        this.caseTimeline.acknowledged(saved);
        return new WarningDTO.Acknowledged(saved.getAcknowledgedAt());
    }

    private Optional<PunishmentEntity> warning(String id) {
        return this.punishments.findById(id).filter(PunishmentEntity::isWarning);
    }

    private WarningDTO.Response response(PunishmentEntity warning) {
        return new WarningDTO.Response(warning.getIdentifier(), warning.getOwner(), reasonOf(warning),
                actorName(warning.getRecordedByOid()), warning.getCreatedAt() == null ? 0 : warning.getCreatedAt(),
                warning.getAcknowledgedAt());
    }

    private static String reasonOf(PunishmentEntity warning) {
        return warning.getMetaData().get("reason") instanceof String reason ? reason : "";
    }

    private String actorName(UUID oid) {
        return oid == null ? null : this.staffLinks.displayName(oid).orElse(null);
    }

    private static WarningDTO.Error notFound() {
        return new WarningDTO.Error("there is no such warning", ErrorKind.NOT_FOUND);
    }
}
