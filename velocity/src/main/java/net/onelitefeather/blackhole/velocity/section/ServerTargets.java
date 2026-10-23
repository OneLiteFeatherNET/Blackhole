package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerFacts;

import java.util.Collection;
import java.util.Optional;

/**
 * Whether a SERVER section covers a backend server: glob patterns by server name, CloudNet kinds by
 * what CloudNet knows about the server (case-insensitive). Without facts a CloudNet section covers
 * nothing.
 */
public final class ServerTargets {

    private ServerTargets() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static boolean covers(SectionInfo section, String serverName, Optional<ServerFacts> facts) {
        if (section.targetKind() == ServerTargetKind.PATTERN) {
            return ServerPatterns.matchesAny(section.servers(), serverName);
        }
        return facts.map(known -> switch (section.targetKind()) {
            case CLOUDNET_TASK -> containsIgnoreCase(section.servers(), known.taskName());
            case CLOUDNET_GROUP -> known.groups().stream().anyMatch(group -> containsIgnoreCase(section.servers(), group));
            case CLOUDNET_SERVICE -> containsIgnoreCase(section.servers(), known.serviceName());
            case PATTERN -> false;
        }).orElse(false);
    }

    private static boolean containsIgnoreCase(Collection<String> names, String name) {
        return names.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(name));
    }
}
