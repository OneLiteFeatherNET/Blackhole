package net.onelitefeather.blackhole.backend.banid.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.banid.dto.BanIdDTO;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link BanIdApi}, which this class implements. Database
 * work blocks, so the method runs on the blocking executor.
 */
@Validated
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/punishment")
public class BanIdController implements BanIdApi {

    private final BanIdService banIds;

    /**
     * @param banIds issues and reveals Ban IDs
     */
    @Inject
    public BanIdController(BanIdService banIds) {
        this.banIds = banIds;
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.PROXY})
    public HttpResponse<BanIdDTO> reveal(String reference) {
        BanIdDTO result = this.banIds.reveal(reference);
        MutableHttpResponse<BanIdDTO> response = result instanceof BanIdDTO.Error error
                ? HttpResponse.<BanIdDTO>status(error.kind().status()).body(result)
                : HttpResponse.ok(result);
        return response.header("Cache-Control", "no-store");
    }
}
