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
import net.onelitefeather.blackhole.backend.intel.names.NameHistoryService;
import net.onelitefeather.blackhole.backend.intel.names.dto.NameHistoryDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link IntelApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/intel")
public class IntelController implements IntelApi {

    private final SessionService sessions;
    private final NameHistoryService names;

    @Inject
    public IntelController(SessionService sessions, NameHistoryService names) {
        this.sessions = sessions;
        this.names = names;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<SessionDTO> start(SessionDTO.StartRequest request) {
        return respond(this.sessions.start(request));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<SessionDTO> update(String sessionId, SessionDTO.UpdateRequest request) {
        return respond(this.sessions.update(sessionId, request));
    }

    /** VIEWER and above: the panel's read. The proxy is refused. */
    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<SessionDTO> sessions(String owner, int page, int size) {
        return respond(this.sessions.listForOwner(owner, page, size));
    }

    /** VIEWER and above, and the proxy, for the /blackhole &lt;user&gt; names command. */
    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<NameHistoryDTO> names(String owner, int page, int size) {
        return respond(this.names.namesOf(owner, page, size));
    }

    /** VIEWER and above, and the proxy, for the old-name lookup. */
    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<NameHistoryDTO> owners(String name) {
        return respond(this.names.ownersOf(name));
    }

    private static HttpResponse<NameHistoryDTO> respond(NameHistoryDTO result) {
        if (result instanceof NameHistoryDTO.Error error) {
            return HttpResponse.<NameHistoryDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }

    private static HttpResponse<SessionDTO> respond(SessionDTO result) {
        if (result instanceof SessionDTO.Error error) {
            return HttpResponse.<SessionDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
