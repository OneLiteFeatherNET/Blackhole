package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.AreaBanCreateRequest;
import net.onelitefeather.blackhole.client.model.AreaBanResponse;
import net.onelitefeather.blackhole.client.model.GuardPresenceResponse;
import net.onelitefeather.blackhole.client.model.GuardStateResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An in-memory {@link GuardBackend} that records the presence reports and the staff writes, and answers with a
 * set level. A write fails with {@link #writeFailure} when one is set.
 */
final class FakeGuardBackend implements GuardBackend {

    /** One staff write: what was written and the staff member it was made for. */
    record Write(String what, UUID onBehalfOf) {
    }

    final List<String> reports = new ArrayList<>();
    final List<Write> writes = new ArrayList<>();
    final List<AreaBanResponse> activeBans = new ArrayList<>();
    String effectiveLevel = "OFF";
    ApiException failure;
    ApiException writeFailure;

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
        throw new UnsupportedOperationException("not used by the staff commands under test");
    }

    @Override
    public GuardStateResponse setLevel(UUID onBehalfOf, String level) throws ApiException {
        record(new Write("level " + level, onBehalfOf));
        return new GuardStateResponse().manualLevel(level).automaticLevel("OFF").effectiveLevel(level).updatedAt(0L);
    }

    @Override
    public AreaBanResponse createAreaBan(UUID onBehalfOf, AreaBanCreateRequest request) throws ApiException {
        record(new Write("ban " + request.getKind() + " " + request.getValue(), onBehalfOf));
        return new AreaBanResponse().id("created").kind(request.getKind()).value(request.getValue()).reason(request.getReason())
                .source("PROXY").createdAt(0L).active(true);
    }

    @Override
    public List<AreaBanResponse> activeAreaBans() {
        return List.copyOf(this.activeBans);
    }

    @Override
    public AreaBanResponse liftAreaBan(UUID onBehalfOf, String id, String reason) throws ApiException {
        record(new Write("lift " + id, onBehalfOf));
        return new AreaBanResponse().id(id).kind("ASN").value("0").reason(reason).source("PROXY").createdAt(0L).active(false);
    }

    private void record(Write write) throws ApiException {
        if (this.writeFailure != null) {
            throw this.writeFailure;
        }
        this.writes.add(write);
    }
}
