package net.onelitefeather.blackhole.backend.integration;

import java.util.List;

/**
 * The editable and identifying fields of an integration registration, as the service validates them.
 * {@code sourceSystem} and {@code entraClientId} never change after creation: a different source or client
 * is a different registration.
 *
 * @param allowedKinds          the kinds with their schema version, {@code KIND:version}, for example {@code DETECTION:1}
 * @param allowedTasks          CloudNet task names whose dynamic services (<code>&lt;task&gt;-&lt;n&gt;</code>) may report
 * @param allowedStaticServers  exact names of static servers that may report
 */
public record IntegrationSpec(
        String sourceSystem,
        String displayName,
        String entraClientId,
        boolean enabled,
        List<String> allowedKinds,
        List<String> allowedTasks,
        List<String> allowedStaticServers
) {

    public IntegrationSpec {
        allowedKinds = List.copyOf(allowedKinds);
        allowedTasks = List.copyOf(allowedTasks);
        allowedStaticServers = List.copyOf(allowedStaticServers);
    }
}
