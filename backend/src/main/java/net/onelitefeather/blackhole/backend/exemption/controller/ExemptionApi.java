package net.onelitefeather.blackhole.backend.exemption.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.UUID;

public interface ExemptionApi {

    @Operation(
            summary = "Exemption of a player",
            description = "Whether a player is exempt from punishment, from linked staff or from the proxy's permission report, "
                    + "with the rank that protects them. Never contains a UUID or a name.",
            operationId = "getPlayerExemption",
            tags = {"Exemption"}
    )
    @ApiResponse(responseCode = "200", description = "The exemption status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.StatusResponse.class)))
    @ApiResponse(responseCode = "400", description = "The owner is not a SHA-512 hash",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @Get("/{owner}")
    HttpResponse<ExemptionDTO> status(@PathVariable String owner);

    @Operation(
            summary = "Report the permission exemption of an online player",
            description = "Called by the proxy at login and with each enforcement request. Replaces the permission rows of the owner; "
                    + "empty flags remove them. Linked-staff rows are not changed.",
            operationId = "reportPlayerExemption",
            tags = {"Exemption"}
    )
    @ApiResponse(responseCode = "200", description = "The status after the report",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.StatusResponse.class)))
    @ApiResponse(responseCode = "400", description = "The owner or a flag is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not the proxy")
    @Post("/report")
    HttpResponse<ExemptionDTO> report(@Body ExemptionDTO.ReportRequest request);

    @Operation(
            summary = "Open exemption reviews",
            description = "Automatic punishments held back for exempt players, oldest first. Only an admin may decide them.",
            operationId = "listExemptionReviews",
            tags = {"Exemption"}
    )
    @ApiResponse(responseCode = "200", description = "The open reviews",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.ReviewListResponse.class)))
    @Get("/reviews")
    HttpResponse<ExemptionDTO> reviews();

    @Operation(
            summary = "Apply a held-back punishment",
            description = "Places the punishment the review stands for, with the justification recorded on it. The review is then applied.",
            operationId = "applyExemptionReview",
            tags = {"Exemption"}
    )
    @ApiResponse(responseCode = "200", description = "The review, now applied",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.ReviewResponse.class)))
    @ApiResponse(responseCode = "400", description = "The justification is missing",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "There is no such review",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The review was decided already, or the punishment cannot be placed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @Post("/reviews/{id}/apply")
    HttpResponse<ExemptionDTO> apply(@PathVariable UUID id, @Body ExemptionDTO.DecisionRequest request,
                                     @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Dismiss a held-back punishment",
            description = "Closes the review without a punishment. The score change stays.",
            operationId = "dismissExemptionReview",
            tags = {"Exemption"}
    )
    @ApiResponse(responseCode = "200", description = "The review, now dismissed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.ReviewResponse.class)))
    @ApiResponse(responseCode = "404", description = "There is no such review",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The review was decided already",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExemptionDTO.Error.class)))
    @Post("/reviews/{id}/dismiss")
    HttpResponse<ExemptionDTO> dismiss(@PathVariable UUID id, @Body ExemptionDTO.DecisionRequest request,
                                       @Parameter(hidden = true) Actor actor);
}
