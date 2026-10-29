package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.api.AreaBansApi;
import net.onelitefeather.blackhole.client.api.GuardApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.AreaBanCreateRequest;
import net.onelitefeather.blackhole.client.model.AreaBanLiftRequest;
import net.onelitefeather.blackhole.client.model.AreaBanResponse;
import net.onelitefeather.blackhole.client.model.GuardLevelRequest;
import net.onelitefeather.blackhole.client.model.GuardPresenceRequest;
import net.onelitefeather.blackhole.client.model.GuardPresenceResponse;
import net.onelitefeather.blackhole.client.model.GuardStateResponse;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** The guard and area ban calls over HTTP through the generated {@link GuardApi} and {@link AreaBansApi}. */
public final class HttpGuardBackend implements GuardBackend {

    private final GuardApi guardApi;
    private final AreaBansApi areaBansApi;

    public HttpGuardBackend(@NotNull ApiClient apiClient) {
        this.guardApi = new GuardApi(apiClient);
        this.areaBansApi = new AreaBansApi(apiClient);
    }

    @Override
    public GuardPresenceResponse presence(String proxyId, int staffOnline) throws ApiException {
        return this.guardApi.reportJoinGuardPresence(new GuardPresenceRequest().proxyId(proxyId).staffOnline(staffOnline));
    }

    @Override
    public GuardStateResponse setLevel(String level) throws ApiException {
        return this.guardApi.setJoinGuardLevel(new GuardLevelRequest().level(level));
    }

    @Override
    public AreaBanResponse createAreaBan(AreaBanCreateRequest request) throws ApiException {
        return this.areaBansApi.createAreaBan(request);
    }

    @Override
    public List<AreaBanResponse> activeAreaBans() throws ApiException {
        return this.areaBansApi.listAreaBans(false).getBans();
    }

    @Override
    public AreaBanResponse liftAreaBan(String id, String reason) throws ApiException {
        return this.areaBansApi.liftAreaBan(id, new AreaBanLiftRequest().reason(reason));
    }
}
