package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseProtocolDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseProtocolService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link CaseProtocolApi}. Everything needs {@code VIEWER} or
 * above, because those roles can already read the thread; the proxy has no rule and is denied.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class CaseProtocolController implements CaseProtocolApi {

    private final CaseProtocolService protocols;

    @Inject
    public CaseProtocolController(CaseProtocolService protocols) {
        this.protocols = protocols;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<CaseProtocolDTO> listProtocols(String reference) {
        return toResponse(this.protocols.list(reference));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<CaseProtocolDTO> openProtocol(String reference, UUID evidenceId, Actor actor) {
        return toResponse(this.protocols.view(reference, evidenceId, actor)).header("Cache-Control", "private, no-store");
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<?> downloadProtocol(String reference, UUID evidenceId, @Nullable String format, Actor actor) {
        CaseProtocolDTO result = this.protocols.download(reference, evidenceId, format, actor);
        if (result instanceof CaseProtocolDTO.Download download) {
            String type = download.contentType().equals("application/json") ? download.contentType() : "text/plain; charset=utf-8";
            return HttpResponse.ok(download.content())
                    .contentType(MediaType.of(type))
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
                    .header("Cache-Control", "private, no-store")
                    .header("Content-Disposition", "attachment; filename=\"" + download.fileName() + "\"");
        }
        return toResponse(result);
    }

    private static io.micronaut.http.MutableHttpResponse<CaseProtocolDTO> toResponse(CaseProtocolDTO result) {
        if (result instanceof CaseProtocolDTO.Error error) {
            return HttpResponse.<CaseProtocolDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
