package net.onelitefeather.blackhole.backend.controller;

/**
 * {@link io.micronaut.core.version.annotation.Version} values for the REST API, resolved from
 * the {@code X-API-VERSION} header or an {@code api-version}/{@code v} query parameter (see
 * {@code micronaut.router.versioning} in application.yml) rather than a URI prefix. All
 * business-facing controllers declare {@code @Version(ApiVersion.V1)} at the class level;
 * infra/doc endpoints (health, prometheus, swagger) declare no version and stay outside this
 * scheme entirely.
 */
public final class ApiVersion {

    public static final String V1 = "1";

    private ApiVersion() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
