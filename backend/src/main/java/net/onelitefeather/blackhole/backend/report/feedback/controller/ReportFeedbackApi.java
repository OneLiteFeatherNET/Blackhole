package net.onelitefeather.blackhole.backend.report.feedback.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.report.feedback.dto.ReportFeedbackDTO;

import java.util.UUID;

public interface ReportFeedbackApi {

    @Operation(
            summary = "Get the pending report feedback of a reporter",
            description = "Returns the messages that tell the reporter what became of their resolved reports and have "
                    + "not expired yet, oldest first. A message says whether action was taken, never which punishment.",
            operationId = "getPendingReportFeedback",
            tags = {"Report"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The pending messages, possibly none",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportFeedbackDTO.ListResponse.class))
    )
    @Validated
    @Get("/feedback/{owner}")
    HttpResponse<ReportFeedbackDTO.ListResponse> pending(
            @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner);

    @Operation(
            summary = "Acknowledge report feedback",
            description = "Deletes a message after it was shown to the reporter. Acknowledging an unknown or already "
                    + "deleted message succeeds as well, so the call can be repeated safely.",
            operationId = "acknowledgeReportFeedback",
            tags = {"Report"}
    )
    @ApiResponse(responseCode = "204", description = "The message is gone")
    @Post("/feedback/{identifier}/ack")
    HttpResponse<Void> acknowledge(UUID identifier);
}
