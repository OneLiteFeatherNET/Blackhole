package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.invoker.ApiException;

/** Builds the refusals the backend answers with, as the generated client hands them over. No network involved. */
final class Refusals {

    private Refusals() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param status  the HTTP status of the refusal
     * @param message the backend's error message, which may start with a stable code such as STAFF_ROLE_STALE
     * @param kind    the backend's error kind, such as FORBIDDEN_DURATION
     */
    static ApiException withBody(int status, String message, String kind) {
        return new ApiException(status, "HTTP " + status, null,
                "{\"errorMessage\":\"" + message + "\",\"kind\":\"" + kind + "\"}");
    }

    /** A refusal whose body is missing, so only the status tells what happened. */
    static ApiException withoutBody(int status) {
        return new ApiException(status, "HTTP " + status, null, null);
    }
}
