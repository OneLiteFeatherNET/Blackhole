package net.onelitefeather.blackhole.backend.exemption;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.utils.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The admin queue of automatic punishments held back for exempt players. The ELO engine opens an item instead of punishing;
 * an admin applies the punishment with a justification, or dismisses it. A decided item is never decided again.
 */
@Singleton
public class ExemptionReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(ExemptionReviewService.class);

    private final ExemptionReviewRepository reviews;
    private final Provider<SectionPunisher> punisher;
    private final SectionService sections;
    private final ExemptionGuard guard;
    private final ExemptionService exemptions;
    private final Clock clock;

    public ExemptionReviewService(ExemptionReviewRepository reviews, Provider<SectionPunisher> punisher, SectionService sections,
                                  ExemptionGuard guard, ExemptionService exemptions, Clock clock) {
        this.reviews = reviews;
        this.punisher = punisher;
        this.sections = sections;
        this.guard = guard;
        this.exemptions = exemptions;
        this.clock = clock;
    }

    /**
     * Opens a review item for an automatic punishment that was held back.
     *
     * @param tier the punishment that would have applied: {@code SOFT} or {@code PERMANENT}
     */
    public void open(String owner, String sectionKey, String tier, int scoreAfter) {
        this.reviews.save(new ExemptionReviewEntity(UUID.randomUUID(), owner, sectionKey, tier, scoreAfter, this.clock.millis()));
    }

    /** The open review items, oldest first. */
    public ExemptionDTO list() {
        List<ExemptionDTO.Review> items = this.reviews.findByState(ExemptionReviewState.OPEN.name()).stream()
                .sorted(java.util.Comparator.comparingLong(ExemptionReviewEntity::getCrossedAt))
                .map(ExemptionReviewService::toDto)
                .toList();
        return new ExemptionDTO.ReviewListResponse(items);
    }

    /**
     * Applies the held-back punishment with the admin's justification, or dismisses it.
     *
     * @param apply {@code true} to apply, {@code false} to dismiss
     * @return the {@link ExemptionDTO.ReviewResponse}, or an {@link ExemptionDTO.Error}: 400 without a valid justification, 404 for an
     * unknown item, 409 when it was decided already or cannot be placed; nothing changed in every error case
     */
    public ExemptionDTO decide(UUID id, boolean apply, @Nullable String justification, Actor admin) {
        String reason = justification == null ? "" : justification.strip();
        if (apply || !reason.isEmpty()) {
            Optional<String> problem = ExemptionGuard.justificationProblem(reason);
            if (problem.isPresent()) {
                return new ExemptionDTO.Error(problem.get(), ErrorKind.VALIDATION);
            }
        }
        Optional<ExemptionReviewEntity> found = this.reviews.findById(id);
        if (found.isEmpty()) {
            return new ExemptionDTO.Error("there is no exemption review " + id, ErrorKind.NOT_FOUND);
        }
        ExemptionReviewEntity review = found.get();
        if (!ExemptionReviewState.OPEN.name().equals(review.getState())) {
            return new ExemptionDTO.Error("the review was decided already", ErrorKind.CONFLICT);
        }
        long now = this.clock.millis();
        if (apply) {
            ExemptionDTO.Error failed = applyHeld(review, reason, admin);
            if (failed != null) {
                return failed;
            }
            review.decide(ExemptionReviewState.APPLIED, admin.oid(), now, reason);
        } else {
            review.decide(ExemptionReviewState.DISMISSED, admin.oid(), now, reason.isEmpty() ? null : reason);
        }
        this.reviews.update(review);
        return new ExemptionDTO.ReviewResponse(toDto(review));
    }

    /** Places the punishment the review stands for, through the same placement as an automatic punishment, with the override recorded. */
    @Nullable
    private ExemptionDTO.Error applyHeld(ExemptionReviewEntity review, String reason, Actor admin) {
        Optional<SectionDTO.Response> section = this.sections.find(review.getSectionKey());
        if (section.isEmpty() || !section.get().enabled()) {
            return new ExemptionDTO.Error("the section of the review is missing or disabled", ErrorKind.CONFLICT);
        }
        Map<String, Object> record = new HashMap<>();
        record.put("justification", reason);
        record.put("targetRank", this.exemptions.protectionOf(review.getOwner(), review.getSectionKey())
                .map(protection -> protection.rank().name()).orElse("NONE"));
        record.put("source", String.join(",", this.exemptions.protectionOf(review.getOwner(), review.getSectionKey())
                .map(ExemptionService.Protection::sources).orElse(java.util.List.of())));
        record.put("adminOid", admin.oid().toString());
        record.put("exemptionReview", review.getId().toString());
        Map<String, Object> metadata = Map.of(ExemptionGuard.OVERRIDE_METADATA_KEY, record);

        Optional<String> placed;
        if ("PERMANENT".equals(review.getTier())) {
            placed = this.punisher.get().applyPermanent(review.getOwner(), section.get(), metadata);
        } else {
            Optional<Duration> duration = Durations.parsePositive(section.get().softDuration());
            if (duration.isEmpty()) {
                return new ExemptionDTO.Error("the section has no valid soft duration", ErrorKind.CONFLICT);
            }
            placed = this.punisher.get().applySoft(review.getOwner(), section.get(), duration.get(), metadata);
        }
        if (placed.isEmpty()) {
            LOG.warn("Exemption review {} could not be placed in section {}", review.getId(), review.getSectionKey());
            return new ExemptionDTO.Error("the punishment could not be placed, the player may already be punished", ErrorKind.CONFLICT);
        }
        this.guard.announceOverride(review.getOwner(), review.getSectionKey(), record);
        return null;
    }

    private static ExemptionDTO.Review toDto(ExemptionReviewEntity review) {
        return new ExemptionDTO.Review(review.getId().toString(), review.getOwner(), review.getSectionKey(), review.getTier(),
                review.getScoreAfter(), review.getCrossedAt(), review.getState(), review.getJustification());
    }
}
