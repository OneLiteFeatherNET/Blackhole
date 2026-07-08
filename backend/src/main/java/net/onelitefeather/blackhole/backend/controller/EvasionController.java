package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.dto.EvasionRecordDTO;
import net.onelitefeather.blackhole.backend.evasion.IpCorrelationService;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Records a login sighting for ban-evasion detection. Called by the Velocity proxy at login,
 * parallel to its existing UUID hashing - see {@code IpCorrelationService} for the actual
 * privacy-preserving correlation mechanics.
 */
@Secured(Roles.SERVICE)
@Controller(ApiVersion.V1 + "/evasion")
public class EvasionController {

    private final IpCorrelationService ipCorrelationService;

    @Inject
    public EvasionController(IpCorrelationService ipCorrelationService) {
        this.ipCorrelationService = ipCorrelationService;
    }

    @Operation(
            summary = "Record a login sighting for ban-evasion detection",
            description = "Computes a keyed hash of the IP (never stores it raw) and checks whether multiple distinct owners share it recently",
            operationId = "recordEvasionSighting",
            tags = {"Evasion"}
    )
    @ApiResponse(responseCode = "200", description = "Recorded")
    @ApiResponse(responseCode = "503", description = "blackhole.evasion.ip-salt is not configured - evasion detection is disabled")
    @Validated
    @Post("/{tenantId}/record")
    public HttpResponse<?> record(UUID tenantId, @Body @Valid EvasionRecordDTO request) {
        if (!this.ipCorrelationService.isConfigured()) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE, "Ban-evasion detection is not configured");
        }
        this.ipCorrelationService.recordLogin(tenantId, request.owner(), request.ip());
        return HttpResponse.ok();
    }
}
