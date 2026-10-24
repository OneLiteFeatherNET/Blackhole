package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.service.AppealEligibilityService;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicAppealDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicPunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.phoca.metadata.Expirable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Files and reads the appeal of the session's punishment, and tells the player where their right to appeal
 * stands. The punishment always comes from the session. Nothing the player does here records an ineligible
 * attempt, and nothing here returns the statement, the decision note, the checklist or a reviewer.
 *
 * <p>At most one appeal per punishment is open: the check here gives the player a precise answer, the unique
 * partial index of {@code appeals} is what holds when two requests race.</p>
 */
@Singleton
public class PublicAppealService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAppealService.class);

    private final PunishmentRepository punishments;
    private final PublicAppealReader appeals;
    private final AppealEligibilityService eligibility;
    private final DomainEventPublisher events;
    private final BanIdService banIds;
    private final Clock clock;
    private final int maxLength;

    public PublicAppealService(
            PunishmentRepository punishments, PublicAppealReader appeals, AppealEligibilityService eligibility, DomainEventPublisher events,
            BanIdService banIds, Clock clock, @Value("${blackhole.public.message.max-length:2000}") int maxLength
    ) {
        this.punishments = punishments;
        this.appeals = appeals;
        this.eligibility = eligibility;
        this.events = events;
        this.banIds = banIds;
        this.clock = clock;
        this.maxLength = Math.min(maxLength, net.onelitefeather.blackhole.backend.casetimeline.MessageRules.MAX_MESSAGE);
    }

    /**
     * Where the right to appeal stands for a punishment.
     *
     * @param leaked whether the Ban ID is marked leaked now
     */
    public PublicPunishmentDTO.AppealSummary summary(PunishmentEntity punishment, boolean leaked, List<PublicAppealReader.Row> history, long now) {
        boolean open = history.stream().anyMatch(PublicAppealReader.Row::open);
        if (leaked) {
            return summary(open ? PublicPunishmentDTO.AppealState.FROZEN : PublicPunishmentDTO.AppealState.FORFEITED, null);
        }
        if (open) {
            return summary(PublicPunishmentDTO.AppealState.OPEN, null);
        }
        boolean granted = history.stream().anyMatch(row -> row.status() == AppealStatus.GRANTED_FULL_LIFT
                || row.status() == AppealStatus.GRANTED_DURATION_REDUCTION);
        if (granted || PublicPunishmentState.of(punishment, history, now) != PublicPunishmentDTO.State.ACTIVE) {
            return summary(PublicPunishmentDTO.AppealState.DECIDED, null);
        }
        AppealEligibilityService.Availability availability = this.eligibility.availability(punishment, now);
        return switch (availability.state()) {
            case AVAILABLE -> summary(PublicPunishmentDTO.AppealState.AVAILABLE, null);
            case NOT_YET -> summary(PublicPunishmentDTO.AppealState.NOT_YET, availability.availableFrom());
            case COOLDOWN -> summary(PublicPunishmentDTO.AppealState.COOLDOWN, availability.availableFrom());
        };
    }

    /**
     * Files the appeal.
     *
     * @return the {@link PublicAppealDTO.Status}, or an error: 400 / 422 for the statement, 409 with
     *         {@code FORFEITED}, {@code FROZEN} or {@code NOT_ELIGIBLE} (with {@code availableFrom} when the player only has to wait)
     */
    public PublicAppealDTO file(PlayerSession session, PublicAppealDTO.FileRequest request) {
        PublicText.Checked statement = PublicText.check(request == null ? null : request.statement(), this.maxLength, this.banIds);
        if (!statement.ok()) {
            return error(statement.code(), statement.failure());
        }
        PunishmentEntity punishment = this.punishments.findById(session.punishmentIdentifier()).orElse(null);
        if (punishment == null) {
            return error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
        }
        long now = this.clock.millis();
        List<PublicAppealReader.Row> history = this.appeals.list(punishment.getIdentifier());
        PublicPunishmentDTO.AppealSummary summary = summary(punishment, session.scope() == PlayerScope.LIMITED, history, now);
        PublicAppealDTO.Error refused = refusal(summary);
        if (refused != null) {
            return refused;
        }
        AppealEntity saved;
        try {
            Optional<AppealEntity> filed = this.eligibility.fileIfEligible(punishment, statement.text());
            if (filed.isEmpty()) {
                return notEligible(summary(punishment, false, history, now));
            }
            saved = filed.get();
        } catch (RuntimeException e) {
            if (this.appeals.list(punishment.getIdentifier()).stream().anyMatch(PublicAppealReader.Row::open)) {
                // Another request filed first and the unique index of open appeals held: one appeal, this one is refused.
                return new PublicAppealDTO.Error("NOT_ELIGIBLE", null, PublicErrorKind.CONFLICT);
            }
            throw e;
        }
        publishSubmitted(saved, punishment);
        return status(saved.getStatus(), saved.getCreatedAt(), punishment);
    }

    /**
     * The player's appeal: the open one, else the latest decided.
     *
     * @return the {@link PublicAppealDTO.Status}, or an error: 403 for a leaked Ban ID, 409 {@code NO_APPEAL}
     */
    public PublicAppealDTO read(PlayerSession session) {
        if (session.scope() == PlayerScope.LIMITED) {
            return error("FORBIDDEN", PublicErrorKind.FORBIDDEN);
        }
        PunishmentEntity punishment = this.punishments.findById(session.punishmentIdentifier()).orElse(null);
        if (punishment == null) {
            return error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
        }
        List<PublicAppealReader.Row> history = this.appeals.list(punishment.getIdentifier());
        PublicAppealReader.Row current = history.stream().filter(PublicAppealReader.Row::open).findFirst()
                .orElse(history.isEmpty() ? null : history.getFirst());
        if (current == null) {
            return error("NO_APPEAL", PublicErrorKind.CONFLICT);
        }
        return status(current.status(), current.createdAt(), punishment);
    }

    private PublicAppealDTO.Error refusal(PublicPunishmentDTO.AppealSummary summary) {
        return switch (summary.state()) {
            case AVAILABLE -> null;
            case FORFEITED -> new PublicAppealDTO.Error("FORFEITED", null, PublicErrorKind.CONFLICT);
            case FROZEN -> new PublicAppealDTO.Error("FROZEN", null, PublicErrorKind.CONFLICT);
            case NOT_YET, COOLDOWN, OPEN, DECIDED -> notEligible(summary);
        };
    }

    private static PublicAppealDTO.Error notEligible(PublicPunishmentDTO.AppealSummary summary) {
        return new PublicAppealDTO.Error("NOT_ELIGIBLE", summary.availableFrom(), PublicErrorKind.CONFLICT);
    }

    private static PublicAppealDTO.Status status(AppealStatus status, long filedAt, PunishmentEntity punishment) {
        PublicAppealDTO.Decision decision = switch (status) {
            case DENIED -> PublicAppealDTO.Decision.DENIED;
            case GRANTED_FULL_LIFT -> PublicAppealDTO.Decision.GRANTED_LIFT;
            case GRANTED_DURATION_REDUCTION -> PublicAppealDTO.Decision.GRANTED_REDUCTION;
            default -> null;
        };
        String name = decision == null || decision == PublicAppealDTO.Decision.DENIED ? status.name() : decision.name();
        String newExpiresAt = decision == PublicAppealDTO.Decision.GRANTED_REDUCTION
                && punishment.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE) instanceof Number expiry
                ? Instant.ofEpochMilli(expiry.longValue()).toString() : null;
        return new PublicAppealDTO.Status(name, decision, newExpiresAt, Instant.ofEpochMilli(filedAt).toString());
    }

    private static PublicPunishmentDTO.AppealSummary summary(PublicPunishmentDTO.AppealState state, Long availableFrom) {
        return new PublicPunishmentDTO.AppealSummary(state, availableFrom == null ? null : Instant.ofEpochMilli(availableFrom).toString());
    }

    private static PublicAppealDTO.Error error(String code, PublicErrorKind kind) {
        return new PublicAppealDTO.Error(code, null, kind);
    }

    /** The same event as a staff-recorded appeal; ids and the hashed owner only. A broker failure never fails the filing. */
    private void publishSubmitted(AppealEntity appeal, PunishmentEntity punishment) {
        try {
            this.events.publish("appeal.submitted", Map.of(
                    "appealIdentifier", appeal.getIdentifier().toString(),
                    "owner", punishment.getOwner(),
                    "punishmentIdentifier", punishment.getIdentifier(),
                    "eligible", true));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish appeal.submitted for appeal {} ({})", appeal.getIdentifier(), e.getClass().getSimpleName());
        }
    }
}
