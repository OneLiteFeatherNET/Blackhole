package net.onelitefeather.blackhole.velocity.cloudnet;

import java.util.Optional;

/** The view of a proxy without CloudNet: it knows no server. */
public final class NoCloudNetView implements CloudNetView {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Optional<ServerFacts> facts(String serverName) {
        return Optional.empty();
    }

    @Override
    public Catalogue catalogue() {
        return Catalogue.empty();
    }

    @Override
    public Optional<String> ownName() {
        return Optional.empty();
    }

    @Override
    public void onChange(Runnable listener) {
        // Nothing ever changes.
    }
}
