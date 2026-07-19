package net.onelitefeather.blackhole.backend.punishment.controller;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentApplicationService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.core.version.annotation.Version;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileResponse;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link PunishmentApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/punishment")
public class PunishmentEntityController implements PunishmentApi {

    private final PunishmentApplicationService punishmentApplicationService;

    /**
     * Create a new PunishmentEntityController with the given values.
     *
     * @param punishmentApplicationService applies a punishment template to a profile, and lists punishments
     */
    @Inject
    public PunishmentEntityController(
            PunishmentApplicationService punishmentApplicationService
    ) {
        this.punishmentApplicationService = punishmentApplicationService;
    }

    @Override
    public HttpResponse<PunishProfileResponse> add(String owner, UUID templateId, UUID source) {
        var profile = this.punishmentApplicationService.apply(owner, templateId, source);
        if (profile.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.get().toDTO());
    }

    @Override
    public HttpResponse<PunishProfileResponse> revokeBan(String owner, UUID source) {
        var profile = this.punishmentApplicationService.revokeBan(owner, source);
        if (profile.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.get().toDTO());
    }

    @Override
    public HttpResponse<PunishProfileResponse> revokeMute(String owner, UUID source) {
        var profile = this.punishmentApplicationService.revokeMute(owner, source);
        if (profile.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.get().toDTO());
    }

    @Override
    public HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable) {
        Page<PunishmentEntity> entries = this.punishmentApplicationService.findAll(pageable);
        return HttpResponse.ok(entries.map(PunishmentEntity::toDTO));
    }
}
