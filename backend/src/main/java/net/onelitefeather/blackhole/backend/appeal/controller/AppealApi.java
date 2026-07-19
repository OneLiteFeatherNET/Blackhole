package net.onelitefeather.blackhole.backend.appeal.controller;

import net.onelitefeather.blackhole.backend.appeal.dto.AppealDTO;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealSubmissionDTO;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

import java.util.UUID;

public interface AppealApi {

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
    HttpResponse<?> submit(@Body @Valid AppealSubmissionDTO submission);

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
    HttpResponse<Page<AppealDTO>> getAll(Pageable pageable);

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
    HttpResponse<?> review(UUID identifier, @Body @Valid AppealReviewDTO review);
}
