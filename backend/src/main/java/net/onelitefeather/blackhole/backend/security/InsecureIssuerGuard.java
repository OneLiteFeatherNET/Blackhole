package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;

import java.net.URI;
import java.util.Set;

/**
 * Refuses to start when the token issuer is a plain http or localhost address outside the
 * {@code dev} and {@code test} environments, unless {@code blackhole.security.allow-insecure-issuer}
 * is set. The local-dev defaults point at the mock OIDC server; this keeps them out of production.
 * There is deliberately no setting that turns authentication off.
 */
@Context
public class InsecureIssuerGuard {

    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(Environment.DEVELOPMENT, Environment.TEST);

    public InsecureIssuerGuard(
            Environment environment,
            SecurityProperties properties,
            @Value("${micronaut.security.token.jwt.claims-validators.issuer:}") String issuer
    ) {
        check(issuer, environment.getActiveNames(), properties.allowInsecureIssuer());
    }

    /**
     * @throws IllegalStateException when {@code issuer} is insecure, no local environment is active and
     *                               the override is not set
     */
    static void check(String issuer, Set<String> activeEnvironments, boolean allowInsecure) {
        if (allowInsecure || activeEnvironments.stream().anyMatch(LOCAL_ENVIRONMENTS::contains)) {
            return;
        }
        if (isInsecure(issuer)) {
            throw new IllegalStateException("The token issuer '" + issuer + "' is a plain http or localhost address, "
                    + "which is only allowed in the dev and test environments. Set BLACKHOLE_AUTH_ISSUER to the "
                    + "https issuer of your identity provider (or blackhole.security.allow-insecure-issuer=true "
                    + "for a deliberate exception).");
        }
    }

    static boolean isInsecure(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(issuer.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return !"https".equalsIgnoreCase(uri.getScheme())
                    || host.equals("localhost") || host.endsWith(".localhost")
                    || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
