package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.EligibilityResult;
import net.onelitefeather.blackhole.backend.appeal.service.AppealEligibilityService;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.MessageRules;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.phoca.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Files the appeal of a ticket the way the portal does: an open appeal is reused, otherwise the checklist is evaluated read-only and only an
 * eligible attempt is recorded (through the existing {@link AppealEligibilityService#submitAppeal}, which also records {@code appeal.filed}).
 * At most one appeal per punishment is open (a unique partial index), so a concurrent filing that loses attaches to the winner.
 */
@Singleton
public class PortalTicketAppeals implements TicketAppeals {

    private static final Logger LOGGER = LoggerFactory.getLogger(PortalTicketAppeals.class);
    private static final List<AppealStatus> OPEN = List.of(AppealStatus.ELIGIBLE_PENDING_REVIEW, AppealStatus.IN_REVIEW);
    private static final List<AppealStatus> PRIOR = List.of(AppealStatus.DENIED, AppealStatus.INELIGIBLE);

    private final AppealRepository appeals;
    private final AppealEligibilityService eligibility;
    private final BanIdService banIds;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final int cooldownDays;

    public PortalTicketAppeals(
            AppealRepository appeals, AppealEligibilityService eligibility, BanIdService banIds, DomainEventPublisher events, Clock clock,
            @Value("${blackhole.appeal.repeat-appeal-cooldown-days:7}") int cooldownDays
    ) {
        this.appeals = appeals;
        this.eligibility = eligibility;
        this.banIds = banIds;
        this.events = events;
        this.clock = clock;
        this.cooldownDays = cooldownDays;
    }

    @Override
    public Outcome attach(PunishmentEntity punishment, @Nullable String statement) {
        String id = punishment.getIdentifier();
        Optional<AppealEntity> open = openAppeal(id);
        if (open.isPresent()) {
            return new Outcome.Existing(open.get().getIdentifier());
        }
        EligibilityResult result = this.eligibility.evaluate(punishment, punishment.getOwner());
        if (!result.eligible()) {
            return new Outcome.NotYet(availableFrom(punishment, result));
        }
        if (statement == null || statement.isBlank()) {
            return new Outcome.StatementMissing();
        }
        String problem = MessageRules.check(statement, this.banIds);
        if (problem != null) {
            return new Outcome.StatementInvalid(problem);
        }
        AppealEntity saved;
        try {
            saved = this.eligibility.submitAppeal(id, punishment.getOwner(), statement.strip()).orElse(null);
        } catch (RuntimeException e) {
            // The unique index on open appeals: a concurrent filing won, attach to it.
            Optional<AppealEntity> winner = openAppeal(id);
            if (winner.isPresent()) {
                return new Outcome.Existing(winner.get().getIdentifier());
            }
            throw e;
        }
        if (saved == null) {
            throw new IllegalStateException("the punishment disappeared while its appeal was filed");
        }
        publishSubmitted(saved, punishment);
        return new Outcome.Filed(saved.getIdentifier());
    }

    private Optional<AppealEntity> openAppeal(String punishmentIdentifier) {
        return this.appeals.findByPunishmentIdentifierAndStatusIn(punishmentIdentifier, OPEN).stream().findFirst();
    }

    private long availableFrom(PunishmentEntity punishment, EligibilityResult result) {
        long now = this.clock.millis();
        Object created = punishment.getMetaData().get(Metadata.META_DATA_KEY_CREATION_DATE);
        Object minDays = result.checklistSnapshot().get("minDaysRequired");
        List<Long> prior = this.appeals.findByPunishmentIdentifierAndStatusIn(punishment.getIdentifier(), PRIOR).stream()
                .map(AppealEntity::getCreatedAt).toList();
        OptionalLong from = AppealAvailability.availableFrom(now, created instanceof Number number ? number.longValue() : now,
                minDays instanceof Number number ? number.intValue() : 0, prior, this.cooldownDays);
        return from.orElse(now);
    }

    private void publishSubmitted(AppealEntity appeal, PunishmentEntity punishment) {
        try {
            this.events.publish("appeal.submitted", Map.of(
                    "appealIdentifier", appeal.getIdentifier().toString(),
                    "owner", punishment.getOwner(),
                    "punishmentIdentifier", punishment.getIdentifier(),
                    "eligible", appeal.getStatus() == AppealStatus.ELIGIBLE_PENDING_REVIEW));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish appeal.submitted for appeal {}", appeal.getIdentifier(), e);
        }
    }
}
