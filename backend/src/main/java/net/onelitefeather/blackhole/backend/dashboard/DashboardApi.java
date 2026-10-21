package net.onelitefeather.blackhole.backend.dashboard;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

public interface DashboardApi {

    @Operation(
            summary = "Dashboard summary",
            description = "Active punishments per section, open reports, pending appeals, escalations of the "
                    + "last 7 days and the latest automatic permanent punishments",
            operationId = "getDashboardSummary",
            tags = {"Dashboard"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The current summary",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = DashboardDTO.Response.class)
            )
    )
    @Get("/summary")
    HttpResponse<DashboardDTO> summary();
}
