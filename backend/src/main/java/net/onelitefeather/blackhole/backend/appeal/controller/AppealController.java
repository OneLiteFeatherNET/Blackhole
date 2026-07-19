package net.onelitefeather.blackhole.backend.appeal.controller;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
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
 * {@code AppealRepository} is only used here for the plain, read-only appeal listing - the same
 * accepted exception {@code EloController} makes for its own read endpoints - every other
 * repository access lives in {@link AppealEligibilityService}/{@link AppealDecisionService}.
 */
@Version(ApiVersion.V1)
@Controller("/appeal")
public class AppealController {

    private final AppealRepository appealRepository;
    private final AppealEligibilityService eligibilityService;
    private final AppealDecisionService decisionService;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public AppealController(
            AppealRepository appealRepository,
            AppealEligibilityService eligibilityService,
            AppealDecisionService decisionService,
            DomainEventPublisher eventPublisher
    ) {
        this.appealRepository = appealRepository;
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
