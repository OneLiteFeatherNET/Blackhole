package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.dto.ChatSignalDTO;
import net.onelitefeather.blackhole.backend.elo.ChatToxicityResult;
import net.onelitefeather.blackhole.backend.elo.ChatToxicityService;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.security.TenantContext;

/**
 * The chat side of the dual-ELO system. Scoring runs backend-side (not in the Velocity proxy
 * itself) so the {@code ToxicityScorer} implementation is a single, centrally swappable
 * component even across a network's many proxy instances.
 */
@Secured(Roles.SERVICE)
@Controller("/elo")
public class EloController {

    private final ChatToxicityService chatToxicityService;
    private final TenantContext tenantContext;

    @Inject
    public EloController(ChatToxicityService chatToxicityService, TenantContext tenantContext) {
        this.chatToxicityService = chatToxicityService;
        this.tenantContext = tenantContext;
    }

    @Operation(
            summary = "Score a chat message for toxicity",
            description = "Scores a chat message; if flagged, records evidence (a hash, never the raw text) and applies a chat-ELO delta",
            operationId = "submitChatSignal",
            tags = {"Elo"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Scoring result",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatToxicityResult.class))
    )
    @Validated
    @Post("/chat")
    public HttpResponse<ChatToxicityResult> chat(@Body @Valid ChatSignalDTO signal) {
        this.tenantContext.requireTenantAccess(signal.tenantId());
        ChatToxicityResult result = this.chatToxicityService.evaluate(signal.tenantId(), signal.owner(), signal.message());
        return HttpResponse.ok(result);
    }
}
