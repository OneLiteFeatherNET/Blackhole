package net.onelitefeather.blackhole.velocity.cloudnet;

import java.util.Set;

/**
 * What CloudNet knows about a backend server, free of any CloudNet type.
 *
 * @param serviceName the CloudNet service name, e.g. {@code BedWars-3}
 * @param taskName    the task the service belongs to, e.g. {@code BedWars}
 * @param groups      the groups the service belongs to
 */
public record ServerFacts(String serviceName, String taskName, Set<String> groups) {

    public ServerFacts {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
