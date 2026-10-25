package net.onelitefeather.blackhole.backend.inbox;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

@Version(ApiVersion.V1)
@Controller("/inbox")
public class InboxController implements InboxApi {

    private final InboxService inboxService;

    @Inject
    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<InboxDTO> summary() {
        InboxDTO result = this.inboxService.summary();
        return switch (result) {
            case InboxDTO.Response response -> HttpResponse.ok(response);
            case InboxDTO.Error error -> HttpResponse.<InboxDTO>status(error.kind().status()).body(error);
        };
    }
}
