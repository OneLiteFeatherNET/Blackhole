package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link EvidenceShareApi}. Sharing and revoking need {@code MODERATOR}; the list and the preview
 * need {@code VIEWER}. The proxy, the Discord bot and player sessions have no rule here and are denied.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class EvidenceShareController implements EvidenceShareApi {

    private final EvidenceShareService shares;

    @Inject
    public EvidenceShareController(EvidenceShareService shares) {
        this.shares = shares;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<EvidenceShareDTO> preview(String reference, EvidenceShareDTO.PreviewRequest request) {
        return toResponse(this.shares.preview(reference, request));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<EvidenceShareDTO> share(String reference, EvidenceShareDTO.CreateRequest request, Actor actor) {
        return toResponse(this.shares.share(reference, request, actor));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<EvidenceShareDTO> list(String reference) {
        return toResponse(this.shares.list(reference));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<EvidenceShareDTO> revoke(String reference, UUID shareId, EvidenceShareDTO.RevokeRequest request, Actor actor) {
        return toResponse(this.shares.revoke(reference, shareId, request, actor));
    }

    private static HttpResponse<EvidenceShareDTO> toResponse(EvidenceShareDTO result) {
        if (result instanceof EvidenceShareDTO.Error error) {
            return HttpResponse.<EvidenceShareDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
