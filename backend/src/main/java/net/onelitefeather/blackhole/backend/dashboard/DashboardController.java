package net.onelitefeather.blackhole.backend.dashboard;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

@Version(ApiVersion.V1)
@Controller("/dashboard")
public class DashboardController implements DashboardApi {

    private final DashboardService dashboardService;

    @Inject
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Override
    public HttpResponse<DashboardDTO> summary() {
        return HttpResponse.ok(this.dashboardService.summary());
    }
}
