package net.onelitefeather.blackhole.backend.integration;

import java.util.Collection;

/**
 * Whether a server label is inside a registration's scope. A dynamic CloudNet service is named
 * {@code <task>-<n>} with {@code <n>} digits, so {@code Lobby-2} belongs to task {@code Lobby} and {@code Lobbyx-2}
 * does not. A static server matches by its exact name only.
 *
 * <p>The label is reported by the bridge; this check guards against misconfiguration. It does not authenticate.
 */
public final class IntegrationServerScope {

    private IntegrationServerScope() {
    }

    public static boolean allows(String server, Collection<String> allowedTasks, Collection<String> allowedStaticServers) {
        if (server == null || server.isEmpty()) {
            return false;
        }
        if (allowedStaticServers.contains(server)) {
            return true;
        }
        return allowedTasks.stream().anyMatch(task -> isServiceOf(server, task));
    }

    static boolean isServiceOf(String server, String task) {
        String prefix = task + "-";
        if (task.isEmpty() || !server.startsWith(prefix)) {
            return false;
        }
        String number = server.substring(prefix.length());
        return !number.isEmpty() && number.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
