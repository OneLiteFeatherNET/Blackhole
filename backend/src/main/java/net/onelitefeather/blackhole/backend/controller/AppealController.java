package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.appeal.AppealDecisionService;
import net.onelitefeather.blackhole.backend.appeal.AppealEligibilityService;
import net.onelitefeather.blackhole.backend.appeal.DecisionOutcome;
import net.onelitefeather.blackhole.backend.appeal.EligibilityResult;
import net.onelitefeather.blackhole.backend.database.entities.AppealEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.repository.AppealRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.dto.AppealDTO;
import net.onelitefeather.blackhole.backend.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.dto.AppealStatus;
import net.onelitefeather.blackhole.backend.dto.AppealSubmissionDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The neutral re-ban-lift path: a fixed, versioned checklist ({@link AppealEligibilityService})
 * gates whether an appeal is even reviewable at all, then a human reviewer decides within what
 * the checklist allows - never pure algorithmic auto-lift (a farming vector) and never
 * unconstrained human discretion (today's original problem this whole system exists to fix).
 */
@Version(ApiVersion.V1)
@Controller("/appeal")
public class AppealController {

    private static final Set<AppealStatus> VALID_DECISIONS = Set.of(
            AppealStatus.GRANTED_FULL_LIFT, AppealStatus.GRANTED_DURATION_REDUCTION, AppealStatus.DENIED
    );

    private final AppealRepository appealRepository;
    private final PunishmentRepository punishmentRepository;
    private final AppealEligibilityService eligibilityService;
    private final AppealDecisionService decisionService;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public AppealController(
            AppealRepository appealRepository,
            PunishmentRepository punishmentRepository,
            AppealEligibilityService eligibilityService,
            AppealDecisionService decisionService,
            DomainEventPublisher eventPublisher
    ) {
        this.appealRepository = appealRepository;
        this.punishmentRepository = punishmentRepository;
        this.eligibilityService = eligibilityService;
        this.decisionService = decisionService;
        this.eventPublisher = eventPublisher;
    }

    @Operation(
            summary = "Submit an appeal",
            description = "Submits an appeal against a punishment. Immediately evaluated against the eligibility checklist.",
            operationId = "submitAppeal",
            tags = {"Appeal"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Appeal submitted (status reflects whether it passed the eligibility checklist)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Punishment not found")
    @Validated
    @Post("/")
    public HttpResponse<?> submit(@Body @Valid AppealSubmissionDTO submission) {
        PunishmentEntity punishment = this.punishmentRepository.findById(submission.punishmentIdentifier()).orElse(null);
        if (punishment == null) {
            return HttpResponse.notFound();
        }

        EligibilityResult eligibility = this.eligibilityService.evaluate(punishment, submission.appellantHash());
        long now = System.currentTimeMillis();

        AppealEntity appeal = new AppealEntity(
                punishment, submission.appellantHash(), submission.statement(),
                eligibility.eligible() ? AppealStatus.ELIGIBLE_PENDING_REVIEW : AppealStatus.INELIGIBLE,
                eligibility.checklistSnapshot(), now, now, new HashMap<>()
        );
        AppealEntity saved = this.appealRepository.save(appeal);

        this.eventPublisher.publish("appeal.submitted", Map.of(
                "appealIdentifier", saved.getIdentifier().toString(),
                "owner", submission.appellantHash(),
                "punishmentIdentifier", submission.punishmentIdentifier(),
                "eligible", eligibility.eligible()
        ));

        return HttpResponse.ok(saved.toDTO());
    }

    @Operation(
            summary = "Get all appeals",
            description = "Retrieves a paginated list of appeals",
            operationId = "getAppeals",
            tags = {"Appeal"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved appeals",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = AppealDTO.class), arraySchema = @Schema(implementation = Page.class))
            )
    )
    @Get("/")
    public HttpResponse<Page<AppealDTO>> getAll(Pageable pageable) {
        Page<AppealEntity> entities = this.appealRepository.findAll(pageable);
        return HttpResponse.ok(entities.map(AppealEntity::toDTO));
    }

    @Operation(
            summary = "Review an appeal",
            description = "Decides an eligible appeal. Rejects self-review (reviewerId matching the punishment's own source) "
                    + "and full lifts of SEVERE punishments (duration reduction only).",
            operationId = "reviewAppeal",
            tags = {"Appeal"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Appeal decided",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Appeal not found")
    @ApiResponse(responseCode = "400", description = "Invalid decision, or a full lift was attempted on a SEVERE punishment")
    @ApiResponse(responseCode = "403", description = "reviewerId matches the punishment's original source (self-review)")
    @ApiResponse(responseCode = "409", description = "Appeal is not awaiting review, or the punishment is no longer active")
    @Validated
    @Post("/{identifier}/review")
    public HttpResponse<?> review(UUID identifier, @Body @Valid AppealReviewDTO review) {
        AppealEntity appeal = this.appealRepository.findById(identifier).orElse(null);
        if (appeal == null) {
            return HttpResponse.notFound();
        }
        if (appeal.getStatus() != AppealStatus.ELIGIBLE_PENDING_REVIEW && appeal.getStatus() != AppealStatus.IN_REVIEW) {
            return HttpResponse.status(HttpStatus.CONFLICT, "Appeal is not awaiting review");
        }
        if (!VALID_DECISIONS.contains(review.decision())) {
            return HttpResponse.badRequest("decision must be one of " + VALID_DECISIONS);
        }

        PunishmentEntity punishment = appeal.getPunishment();
        if (review.reviewerId().equals(punishment.getSource())) {
            return HttpResponse.status(HttpStatus.FORBIDDEN, "Reviewer must not be the punishment's original source");
        }

        boolean severe = "SEVERE".equals(appeal.getEligibilityCheckResult().get("severityTier"));
        if (severe && review.decision() == AppealStatus.GRANTED_FULL_LIFT) {
            return HttpResponse.badRequest("SEVERE punishments can only receive a duration reduction, never a full lift");
        }
        if (review.decision() == AppealStatus.GRANTED_DURATION_REDUCTION) {
            if (review.newExpirationAt() == null) {
                return HttpResponse.badRequest("newExpirationAt is required for GRANTED_DURATION_REDUCTION");
            }
            if (review.newExpirationAt() <= System.currentTimeMillis()) {
                return HttpResponse.badRequest("newExpirationAt must be in the future");
            }
        }

        DecisionOutcome outcome = this.decisionService.applyDecision(
                punishment, appeal.getAppellantHash(), review.decision(), review.newExpirationAt()
        );
        if (outcome == DecisionOutcome.PUNISHMENT_NOT_ACTIVE) {
            return HttpResponse.status(HttpStatus.CONFLICT, "Punishment is no longer active");
        }

        appeal.setStatus(review.decision());
        appeal.setDecidedBy(review.reviewerId());
        appeal.setDecisionNote(review.decisionNote());
        appeal.setUpdatedAt(System.currentTimeMillis());
        AppealEntity saved = this.appealRepository.update(appeal);

        this.eventPublisher.publish("appeal.resolved", Map.of(
                "appealIdentifier", identifier.toString(),
                "owner", appeal.getAppellantHash(),
                "punishmentIdentifier", punishment.getIdentifier(),
                "decision", review.decision().toString(),
                "type", punishment.getType().toString()
        ));

        return HttpResponse.ok(saved.toDTO());
    }
}
