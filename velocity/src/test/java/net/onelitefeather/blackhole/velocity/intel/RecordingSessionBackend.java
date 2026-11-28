package net.onelitefeather.blackhole.velocity.intel;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.SessionStartRequest;
import net.onelitefeather.blackhole.client.model.SessionUpdateRequest;

import java.util.ArrayList;
import java.util.List;

/** A {@link SessionBackend} that remembers the reports it gets, or fails every one as if the backend were down. */
public final class RecordingSessionBackend implements SessionBackend {

    public record Update(String sessionId, SessionUpdateRequest request) {
    }

    private final boolean available;
    private final List<SessionStartRequest> starts = new ArrayList<>();
    private final List<Update> updates = new ArrayList<>();

    public RecordingSessionBackend() {
        this(true);
    }

    private RecordingSessionBackend(boolean available) {
        this.available = available;
    }

    /** @return a backend that answers every report with a failure */
    public static RecordingSessionBackend unavailable() {
        return new RecordingSessionBackend(false);
    }

    @Override
    public synchronized void record(SessionStartRequest request) throws ApiException {
        if (!available) {
            throw new ApiException(503, "backend down");
        }
        starts.add(request);
    }

    @Override
    public synchronized void update(String sessionId, SessionUpdateRequest request) throws ApiException {
        if (!available) {
            throw new ApiException(503, "backend down");
        }
        updates.add(new Update(sessionId, request));
    }

    public synchronized List<SessionStartRequest> starts() {
        return List.copyOf(starts);
    }

    public synchronized List<Update> updates() {
        return List.copyOf(updates);
    }
}
