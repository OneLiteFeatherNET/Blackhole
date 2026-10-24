package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseParticipantDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseParticipantService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/** Routing and OpenAPI annotations live on {@link CaseParticipantApi}. Erasing needs {@code ADMIN}; the proxy and the bot are denied. */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class CaseParticipantController implements CaseParticipantApi {

    private final CaseParticipantService service;

    @Inject
    public CaseParticipantController(CaseParticipantService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<CaseParticipantDTO> erase(String reference, UUID participantId) {
        CaseParticipantDTO result = this.service.erase(reference, participantId);
        if (result instanceof CaseParticipantDTO.Error error) {
            return HttpResponse.<CaseParticipantDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
