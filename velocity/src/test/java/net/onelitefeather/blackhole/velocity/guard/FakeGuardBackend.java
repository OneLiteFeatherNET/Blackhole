package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.AreaBanCreateRequest;
import net.onelitefeather.blackhole.client.model.AreaBanResponse;
import net.onelitefeather.blackhole.client.model.GuardPresenceResponse;
import net.onelitefeather.blackhole.client.model.GuardStateResponse;

import java.util.ArrayList;
import java.util.List;

/** An in-memory {@link GuardBackend} that records the presence reports and answers with a set level. */
final class FakeGuardBackend implements GuardBackend {

    final List<String> reports = new ArrayList<>();
    String effectiveLevel = "OFF";
    ApiException failure;

    @Override
    public GuardPresenceResponse presence(String proxyId, int staffOnline) throws ApiException {
        if (this.failure != null) {
            throw this.failure;
        }
        this.reports.add(proxyId + ":" + staffOnline);
        return new GuardPresenceResponse().staffPresent(staffOnline > 0).effectiveLevel(this.effectiveLevel);
    }

    @Override
    public GuardStateResponse state() {
        throw new UnsupportedOperationException("not used by the heartbeat");
    }

    @Override
    public GuardStateResponse setLevel(String level) {
        throw new UnsupportedOperationException("not used by the heartbeat");
    }

    @Override
    public AreaBanResponse createAreaBan(AreaBanCreateRequest request) {
        throw new UnsupportedOperationException("not used by the heartbeat");
    }

    @Override
    public List<AreaBanResponse> activeAreaBans() {
        throw new UnsupportedOperationException("not used by the heartbeat");
    }

    @Override
    public AreaBanResponse liftAreaBan(String id, String reason) {
        throw new UnsupportedOperationException("not used by the heartbeat");
    }
}
