package net.onelitefeather.blackhole.velocity.cloudnet;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** An in-memory {@link CloudNetView} the test fills by hand; no CloudNet involved. */
public final class FakeCloudNetView implements CloudNetView {

    private final boolean available;
    private final Map<String, ServerFacts> servers = new HashMap<>();

    public FakeCloudNetView(boolean available) {
        this.available = available;
    }

    /** Registers a running service the way CloudNet's Bridge names it: {@code task-number}. */
    public FakeCloudNetView service(String name, String task, String... groups) {
        this.servers.put(name.toLowerCase(Locale.ROOT), new ServerFacts(name, task, Set.of(groups)));
        return this;
    }

    public void stop(String name) {
        this.servers.remove(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean available() {
        return this.available;
    }

    @Override
    public Optional<ServerFacts> facts(String serverName) {
        return Optional.ofNullable(this.servers.get(serverName.toLowerCase(Locale.ROOT)));
    }
}
