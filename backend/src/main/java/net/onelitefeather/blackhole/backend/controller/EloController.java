package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.entities.EloEventEntity;
import net.onelitefeather.blackhole.backend.database.entities.EloProfileEntity;
import net.onelitefeather.blackhole.backend.database.repository.EloEventRepository;
import net.onelitefeather.blackhole.backend.database.repository.EloProfileRepository;
import net.onelitefeather.blackhole.backend.dto.ChatSignalDTO;
import net.onelitefeather.blackhole.backend.dto.EloEventDTO;
import net.onelitefeather.blackhole.backend.dto.EloProfileDTO;
import net.onelitefeather.blackhole.backend.elo.ChatToxicityResult;
import net.onelitefeather.blackhole.backend.elo.ChatToxicityService;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * The chat side of the dual-ELO system. Scoring runs backend-side (not in the Velocity proxy
 * itself) so the {@code ToxicityScorer} implementation is a single, centrally swappable
 * component even across a network's many proxy instances. Also exposes the Phase 7 dashboard
 * read endpoints for a player's current standing and audit trail.
 */
@Secured(Roles.SERVICE)
@Controller(ApiVersion.V1 + "/elo")
public class EloController {

    private final ChatToxicityService chatToxicityService;
    private final EloProfileRepository profileRepository;
    private final EloEventRepository eventRepository;

    @Inject
    public EloController(
            ChatToxicityService chatToxicityService,
            EloProfileRepository profileRepository,
            EloEventRepository eventRepository
    ) {
        this.chatToxicityService = chatToxicityService;
        this.profileRepository = profileRepository;
        this.eventRepository = eventRepository;
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
        ChatToxicityResult result = this.chatToxicityService.evaluate(signal.owner(), signal.message());
        return HttpResponse.ok(result);
    }

    @Operation(
            summary = "Get a player's current ELO standing",
            description = "Dashboard read endpoint: the current chat/gameplay scores",
            operationId = "getEloProfile",
            tags = {"Elo"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Current ELO profile",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EloProfileDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "No ELO profile exists yet for this owner")
    @Secured({Roles.ADMIN, Roles.STAFF})
    @Get("/{owner}")
    public HttpResponse<EloProfileDTO> getProfile(String owner) {
        EloProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        if (profile == null) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.toDTO());
    }

    @Operation(
            summary = "Get a player's ELO history",
            description = "Dashboard read endpoint: the full audit trail of ELO changes for this owner",
            operationId = "getEloHistory",
            tags = {"Elo"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Paginated ELO event history",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = EloEventDTO.class), arraySchema = @Schema(implementation = Page.class))
            )
    )
    @Secured({Roles.ADMIN, Roles.STAFF})
    @Get("/{owner}/history")
    public HttpResponse<Page<EloEventDTO>> getHistory(String owner, Pageable pageable) {
        Page<EloEventEntity> entries = this.eventRepository.findByOwner(owner, pageable);
        return HttpResponse.ok(entries.map(EloEventEntity::toDTO));
    }
}
