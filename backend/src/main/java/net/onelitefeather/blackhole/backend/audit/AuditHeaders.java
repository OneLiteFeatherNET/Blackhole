package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.http.MutableHttpResponse;
import net.onelitefeather.blackhole.backend.security.Actor;

/** Adds the {@code X-Audit} header to a response whose staff action could not be recorded. */
public final class AuditHeaders {

    public static final String HEADER = "X-Audit";
    public static final String UNRECORDED = "unrecorded";

    private AuditHeaders() {
    }

    /**
     * @param response the response of the staff action made by {@code actor}
     * @return the same response, with {@code X-Audit: unrecorded} when the entry was not stored
     */
    public static <T> MutableHttpResponse<T> audited(MutableHttpResponse<T> response, Actor actor) {
        if (actor.receipt().unrecorded()) {
            response.header(HEADER, UNRECORDED);
        }
        return response;
    }
}
