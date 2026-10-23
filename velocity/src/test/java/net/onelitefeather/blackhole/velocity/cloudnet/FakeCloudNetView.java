package net.onelitefeather.blackhole.velocity.cloudnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** An in-memory {@link CloudNetView} the test fills by hand; no CloudNet involved. */
public final class FakeCloudNetView implements CloudNetView {

    private final boolean available;
    private final Map<String, ServerFacts> servers = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private Catalogue catalogue = Catalogue.empty();
    private String ownName;

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

    public FakeCloudNetView ownName(String name) {
        this.ownName = name;
        return this;
    }

    /** Replaces what CloudNet knows and tells the listeners, as a CloudNet event would. */
    public void change(Catalogue now) {
        this.catalogue = now;
        this.listeners.forEach(Runnable::run);
    }

    public int listeners() {
        return this.listeners.size();
    }

    @Override
    public Catalogue catalogue() {
        return this.catalogue;
    }

    @Override
    public Optional<String> ownName() {
        return Optional.ofNullable(this.ownName);
    }

    @Override
    public void onChange(Runnable listener) {
        this.listeners.add(listener);
    }
}
