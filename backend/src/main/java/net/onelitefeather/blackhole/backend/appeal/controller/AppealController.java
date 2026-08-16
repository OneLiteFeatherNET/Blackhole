package net.onelitefeather.blackhole.backend.appeal.controller;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealDTO;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealSubmissionDTO;
import net.onelitefeather.blackhole.backend.appeal.service.AppealDecisionService;
import net.onelitefeather.blackhole.backend.appeal.service.AppealEligibilityService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.core.version.annotation.Version;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The neutral re-ban-lift path: a fixed, versioned checklist ({@link AppealEligibilityService})
 * gates whether an appeal is even reviewable at all, then a human reviewer decides within what
 * the checklist allows - never pure algorithmic auto-lift (a farming vector) and never
 * unconstrained human discretion (today's original problem this whole system exists to fix).
 * No repository is injected here - all persistence access lives in
 * {@link AppealEligibilityService}/{@link AppealDecisionService}, including the plain, read-only
 * appeal listing. Routing and OpenAPI annotations live on {@link AppealApi}, which this class
 * implements.
 */
@Version(ApiVersion.V1)
@Controller("/appeal")
public class AppealController implements AppealApi {

    private final AppealEligibilityService eligibilityService;
    private final AppealDecisionService decisionService;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public AppealController(
            AppealEligibilityService eligibilityService,
            AppealDecisionService decisionService,
            DomainEventPublisher eventPublisher
    ) {
        this.eligibilityService = eligibilityService;
        this.decisionService = decisionService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public HttpResponse<?> submit(AppealSubmissionDTO submission) {
        Optional<AppealEntity> saved = this.eligibilityService.submitAppeal(
                submission.punishmentIdentifier(), submission.appellantHash(), submission.statement()
        );
        if (saved.isEmpty()) {
            return HttpResponse.notFound();
        }

        AppealEntity appeal = saved.get();
        boolean eligible = appeal.getStatus() == AppealStatus.ELIGIBLE_PENDING_REVIEW;
        this.eventPublisher.publish("appeal.submitted", Map.of(
                "appealIdentifier", appeal.getIdentifier().toString(),
                "owner", submission.appellantHash(),
                "punishmentIdentifier", submission.punishmentIdentifier(),
                "eligible", eligible
        ));

        return HttpResponse.ok(appeal.toDTO());
    }

    @Override
    public HttpResponse<Page<AppealDTO>> getAll(Pageable pageable) {
        Page<AppealEntity> entities = this.eligibilityService.findAll(pageable);
        return HttpResponse.ok(entities.map(AppealEntity::toDTO));
    }

    @Override
    public HttpResponse<?> review(UUID identifier, AppealReviewDTO review) {
        AppealReviewResult result = this.decisionService.reviewAppeal(identifier, review);
        return switch (result.kind()) {
            case NOT_FOUND -> HttpResponse.notFound();
            case NOT_AWAITING_REVIEW, PUNISHMENT_NOT_ACTIVE -> HttpResponse.status(HttpStatus.CONFLICT, result.message());
            case INVALID_DECISION, SEVERE_FULL_LIFT_DISALLOWED, DURATION_REDUCTION_MISSING_EXPIRY, DURATION_REDUCTION_EXPIRY_NOT_FUTURE ->
                    HttpResponse.badRequest(result.message());
            case SELF_REVIEW -> HttpResponse.status(HttpStatus.FORBIDDEN, result.message());
            case DECIDED -> {
                AppealEntity appeal = result.appeal();
                this.eventPublisher.publish("appeal.resolved", Map.of(
                        "appealIdentifier", identifier.toString(),
                        "owner", appeal.getAppellantHash(),
                        "punishmentIdentifier", appeal.getPunishment().getIdentifier(),
                        "decision", review.decision().toString(),
                        "type", appeal.getPunishment().getType().toString()
                ));
                yield HttpResponse.ok(appeal.toDTO());
            }
        };
    }
}
