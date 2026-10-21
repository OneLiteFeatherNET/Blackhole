package net.onelitefeather.blackhole.backend.punishment.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentApplicationService;

/**
 * Routing and OpenAPI annotations live on {@link PunishmentApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/punishment")
public class PunishmentEntityController implements PunishmentApi {

    private final PunishmentApplicationService punishmentApplicationService;

    /**
     * @param punishmentApplicationService applies and revokes punishments, and lists them
     */
    @Inject
    public PunishmentEntityController(PunishmentApplicationService punishmentApplicationService) {
        this.punishmentApplicationService = punishmentApplicationService;
    }

    @Override
    public HttpResponse<PunishmentDTO> apply(PunishmentDTO.ApplyRequest request) {
        return toResponse(this.punishmentApplicationService.apply(request));
    }

    @Override
    public HttpResponse<PunishmentDTO> preview(PunishmentDTO.ApplyRequest request) {
        return toResponse(this.punishmentApplicationService.preview(request));
    }

    @Override
    public HttpResponse<PunishmentDTO> revoke(PunishmentDTO.RevokeRequest request) {
        return toResponse(this.punishmentApplicationService.revoke(request));
    }

    @Override
    public HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.punishmentApplicationService.findAll(pageable));
    }

    private static HttpResponse<PunishmentDTO> toResponse(PunishmentDTO result) {
        if (result instanceof PunishmentDTO.Error error) {
            return HttpResponse.<PunishmentDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
