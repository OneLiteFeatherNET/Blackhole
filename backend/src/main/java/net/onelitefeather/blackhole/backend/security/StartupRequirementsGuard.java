package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses to start outside the {@code dev} and {@code test} environments unless the token audience
 * is the API app registration's client id (a GUID, as Entra v2 puts it in {@code aud}) and a tenant
 * id is configured. The local-dev placeholder audience never matches a real token, and without a
 * tenant id a multi-tenant authority would be trusted.
 */
@Context
public class StartupRequirementsGuard {

    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(Environment.DEVELOPMENT, Environment.TEST);
    private static final Pattern GUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public StartupRequirementsGuard(
            Environment environment,
            SecurityProperties properties,
            @Value("${micronaut.security.token.jwt.claims-validators.audience:}") String audience
    ) {
        check(audience, properties.tenantId(), environment.getActiveNames());
    }

    /**
     * @throws IllegalStateException when no local environment is active and the audience is not a GUID
     *                               or the tenant id is empty
     */
    static void check(String audience, String tenantId, Set<String> activeEnvironments) {
        if (activeEnvironments.stream().anyMatch(LOCAL_ENVIRONMENTS::contains)) {
            return;
        }
        if (audience == null || !GUID.matcher(audience.trim()).matches()) {
            throw new IllegalStateException("The token audience '" + audience + "' is not the API app client id. "
                    + "Outside the dev and test environments BLACKHOLE_AUTH_AUDIENCE must be the GUID of the API "
                    + "app registration (not an api:// URI and not the local placeholder).");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant id is configured. Outside the dev and test environments "
                    + "BLACKHOLE_AUTH_TENANT_ID must be set to the Entra tenant (directory) id.");
        }
    }
}
