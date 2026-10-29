package net.onelitefeather.blackhole.backend.support;

import jakarta.inject.Provider;
import net.onelitefeather.blackhole.backend.exemption.ExemptionGuard;
import net.onelitefeather.blackhole.backend.exemption.ExemptionReviewEntity;
import net.onelitefeather.blackhole.backend.exemption.ExemptionReviewRepository;
import net.onelitefeather.blackhole.backend.exemption.ExemptionReviewService;
import net.onelitefeather.blackhole.backend.exemption.ExemptionService;
import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.section.SectionService;

import java.time.Clock;
import java.util.UUID;

/**
 * The review queue over an in-memory repository. Each test bed builds its own, so tests share nothing.
 */
public final class ExemptionReviewTestBed {

    public final InMemoryRepository<ExemptionReviewRepository, ExemptionReviewEntity, UUID> reviews =
            InMemoryRepository.<ExemptionReviewRepository, ExemptionReviewEntity, UUID>of(
                            ExemptionReviewRepository.class, ExemptionReviewEntity::getId)
                    .finder("findByState", args -> this.reviews.all().stream()
                            .filter(review -> review.getState().equals(args[0])).toList())
                    .build();

    public ExemptionReviewService service(Provider<SectionPunisher> punisher, SectionService sections, ExemptionGuard guard,
                                          ExemptionService exemptions, Clock clock) {
        return new ExemptionReviewService(this.reviews.repository(), punisher, sections, guard, exemptions, clock);
    }
}
