package net.onelitefeather.blackhole.backend.elo.controller;

import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.security.Roles;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.elo.EloEventEntity;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.elo.service.ChatToxicityService;
import net.onelitefeather.blackhole.backend.elo.service.EloService;

/**
 * Scoring runs backend-side (not in the Velocity proxy itself) so the {@code ToxicityScorer}
 * implementation is a single, centrally swappable component even across a network's many proxy
 * instances. Routing and OpenAPI annotations live on {@link EloApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/elo")
public class EloController implements EloApi {

    private final ChatToxicityService chatToxicityService;
    private final EloService eloService;

    @Inject
    public EloController(ChatToxicityService chatToxicityService, EloService eloService) {
        this.chatToxicityService = chatToxicityService;
        this.eloService = eloService;
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.PROXY})
    public HttpResponse<EloDTO.ChatSignalResponse> chat(EloDTO.ChatSignalRequest signal) {
        return HttpResponse.ok(this.chatToxicityService.evaluate(signal.owner(), signal.message()));
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<EloDTO.ProfileResponse> getProfile(String owner) {
        return HttpResponse.ok(this.eloService.getProfile(owner));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<Page<EloDTO.EventResponse>> getHistory(String owner, String section, Pageable pageable) {
        Page<EloEventEntity> entries = this.eloService.getHistory(owner, section, pageable);
        return HttpResponse.ok(entries.map(EloEventEntity::toDTO));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<EloDTO> adjust(String owner, EloDTO.AdjustRequest request) {
        EloDTO result = this.eloService.adjust(owner, request);
        if (result instanceof EloDTO.Error error) {
            return HttpResponse.<EloDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
