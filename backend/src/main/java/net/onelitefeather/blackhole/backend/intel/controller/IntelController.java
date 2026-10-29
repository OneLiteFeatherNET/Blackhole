package net.onelitefeather.blackhole.backend.intel.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.intel.SessionService;
import net.onelitefeather.blackhole.backend.intel.dto.SessionDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link IntelApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/intel")
public class IntelController implements IntelApi {

    private final SessionService sessions;

    @Inject
    public IntelController(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<SessionDTO> start(SessionDTO.StartRequest request) {
        if (!this.sessions.isEnabled()) {
            return HttpResponse.notFound();
        }
        return respond(this.sessions.start(request));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<SessionDTO> update(String sessionId, SessionDTO.UpdateRequest request) {
        if (!this.sessions.isEnabled()) {
            return HttpResponse.notFound();
        }
        return respond(this.sessions.update(sessionId, request));
    }

    /** VIEWER and above: the panel's read. The proxy is refused. */
    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<SessionDTO> sessions(String owner, int page, int size) {
        if (!this.sessions.isEnabled()) {
            return HttpResponse.notFound();
        }
        return respond(this.sessions.listForOwner(owner, page, size));
    }

    private static HttpResponse<SessionDTO> respond(SessionDTO result) {
        if (result instanceof SessionDTO.Error error) {
            return HttpResponse.<SessionDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
