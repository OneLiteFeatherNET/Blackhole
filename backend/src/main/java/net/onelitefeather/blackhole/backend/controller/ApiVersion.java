package net.onelitefeather.blackhole.backend.controller;

/**
 * URI path prefixes for versioned REST API controllers. All business-facing controllers are
 * mounted under a version prefix; infra/doc endpoints (health, prometheus, swagger) are not
 * versioned and stay outside this scheme.
 */
public final class ApiVersion {

    public static final String V1 = "/v1";

    private ApiVersion() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
