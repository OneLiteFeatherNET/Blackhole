package net.onelitefeather.blackhole.velocity.intel;

import net.onelitefeather.blackhole.client.api.IntelApi;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.SessionStartRequest;
import net.onelitefeather.blackhole.client.model.SessionUpdateRequest;
import org.jetbrains.annotations.NotNull;

/** The session calls over HTTP through the generated {@link IntelApi}. */
public final class HttpSessionBackend implements SessionBackend {

    private final IntelApi intelApi;

    public HttpSessionBackend(@NotNull IntelApi intelApi) {
        this.intelApi = intelApi;
    }

    @Override
    public void record(@NotNull SessionStartRequest request) throws ApiException {
        this.intelApi.recordPlayerSession(request);
    }

    @Override
    public void update(@NotNull String sessionId, @NotNull SessionUpdateRequest request) throws ApiException {
        this.intelApi.updatePlayerSession(sessionId, request);
    }
}
