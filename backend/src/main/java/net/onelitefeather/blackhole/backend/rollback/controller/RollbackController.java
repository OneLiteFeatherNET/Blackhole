package net.onelitefeather.blackhole.backend.rollback.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.rollback.RollbackDTO;
import net.onelitefeather.blackhole.backend.rollback.RollbackExecutionService;
import net.onelitefeather.blackhole.backend.rollback.RollbackPreviewService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link RollbackApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/admin/rollback")
public class RollbackController implements RollbackApi {

    private final RollbackPreviewService preview;
    private final RollbackExecutionService execution;

    @Inject
    public RollbackController(RollbackPreviewService preview, RollbackExecutionService execution) {
        this.preview = preview;
        this.execution = execution;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<RollbackDTO> preview(RollbackDTO.PreviewRequest request, Actor actor) {
        return reply(this.preview.preview(request, actor.oid()), HttpStatus.CREATED);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<RollbackDTO> execute(UUID id, RollbackDTO.ExecuteRequest request, @Nullable String idempotencyKey) {
        return reply(this.execution.start(id, request.reason(), idempotencyKey, request.suspendLink()), HttpStatus.ACCEPTED);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<RollbackDTO> get(UUID id) {
        return reply(this.execution.progress(id), HttpStatus.OK);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<RollbackDTO> cancel(UUID id) {
        return reply(this.execution.cancel(id), HttpStatus.OK);
    }

    private static HttpResponse<RollbackDTO> reply(RollbackDTO result, HttpStatus success) {
        return switch (result) {
            case RollbackDTO.Error error -> HttpResponse.<RollbackDTO>status(error.kind().status()).body(error);
            default -> HttpResponse.<RollbackDTO>status(success).body(result);
        };
    }
}
