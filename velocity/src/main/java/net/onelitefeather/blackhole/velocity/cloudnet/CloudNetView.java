package net.onelitefeather.blackhole.velocity.cloudnet;

import java.util.Optional;

/**
 * The proxy's window onto CloudNet. Hides every CloudNet type so the enforcement code and its tests
 * never need the driver on the classpath.
 */
public interface CloudNetView {

    /**
     * @return whether CloudNet backs this view; {@code false} outside CloudNet or when switched off
     */
    boolean available();

    /**
     * @param serverName the Velocity server name (CloudNet's Bridge registers the service name)
     * @return what CloudNet knows about the server, empty if it is not a CloudNet service
     */
    Optional<ServerFacts> facts(String serverName);
}
