package net.onelitefeather.blackhole.backend.publicapi.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.service.PunishmentEvidenceGateway;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicPunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvents;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.phoca.metadata.Expirable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The public facts of the session's own punishment: an allow-list built field by field, so a column added to
 * the entity later is not public until someone adds it here.
 */
@Singleton
public class PublicPunishmentService {

    private final PunishmentRepository punishments;
    private final SectionService sections;
    private final PunishmentEvidenceGateway evidence;
    private final PublicAppealReader appeals;
    private final PublicAppealService appealService;
    private final Clock clock;

    public PublicPunishmentService(
            PunishmentRepository punishments, SectionService sections, PunishmentEvidenceGateway evidence, PublicAppealReader appeals,
            PublicAppealService appealService, Clock clock
    ) {
        this.punishments = punishments;
        this.sections = sections;
        this.evidence = evidence;
        this.appeals = appeals;
        this.appealService = appealService;
        this.clock = clock;
    }

    /** The facts of the session's punishment; works for every scope, a leaked Ban ID sees them and the notice. */
    public PublicPunishmentDTO facts(PlayerSession session) {
        PunishmentEntity punishment = this.punishments.findById(session.punishmentIdentifier()).orElse(null);
        if (punishment == null) {
            return new PublicPunishmentDTO.Error("NOT_FOUND", null, PublicErrorKind.NOT_FOUND);
        }
        long now = this.clock.millis();
        List<PublicAppealReader.Row> history = this.appeals.list(punishment.getIdentifier());
        SectionDTO.Response section = this.sections.find(punishment.getSectionKey()).orElse(null);
        String expiresAt = punishment.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE) instanceof Number expiry
                ? Instant.ofEpochMilli(expiry.longValue()).toString() : null;
        return new PublicPunishmentDTO.Facts(
                punishment.getIdentifier(),
                section == null ? "UNKNOWN" : section.enforcement().name(),
                PunishmentEvents.reasonOf(punishment),
                section == null ? punishment.getSectionKey() : section.displayName(),
                Instant.ofEpochMilli(punishment.getCreatedAt()).toString(),
                punishment.isPermanent(),
                expiresAt,
                PublicPunishmentState.of(punishment, history, now),
                this.evidence.countValid(punishment.getIdentifier()),
                this.appealService.summary(punishment, session.scope() == PlayerScope.LIMITED, history, now));
    }
}
