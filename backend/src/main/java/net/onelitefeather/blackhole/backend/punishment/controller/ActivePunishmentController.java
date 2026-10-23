package net.onelitefeather.blackhole.backend.punishment.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentSort;
import net.onelitefeather.blackhole.backend.punishment.SortDirection;
import net.onelitefeather.blackhole.backend.punishment.dto.ActivePunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.service.ActivePunishmentService;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link ActivePunishmentApi}, which this class implements.
 * Database work blocks, so it runs on the blocking executor. The proxy has no rule here and is denied.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/punishment/active")
public class ActivePunishmentController implements ActivePunishmentApi {

    private final ActivePunishmentService service;

    @Inject
    public ActivePunishmentController(ActivePunishmentService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<ActivePunishmentDTO> list(
            @Nullable EnforcementKind kind, @Nullable String section, @Nullable ServerTargetKind targetKind,
            @Nullable Boolean permanent, @Nullable Long expiresBefore, @Nullable Boolean automatic, @Nullable UUID actor,
            @Nullable ActivePunishmentSort sort, @Nullable SortDirection direction, @Nullable Integer page, @Nullable Integer size
    ) {
        ActivePunishmentDTO result = this.service.list(kind, section, targetKind, permanent, expiresBefore, automatic, actor, sort, direction, page, size);
        if (result instanceof ActivePunishmentDTO.Error error) {
            return HttpResponse.<ActivePunishmentDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
