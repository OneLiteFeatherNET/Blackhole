package net.onelitefeather.blackhole.backend.response;

import io.micronaut.http.HttpStatus;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Why an expected failure happened, carried by a resource's {@code Error} variant so a controller
 * can map it 1:1 to a status without re-deriving the cause.
 */
@Serdeable
public enum ErrorKind {

    /** The request is malformed or breaks a business rule of its own (400). */
    VALIDATION(HttpStatus.BAD_REQUEST),

    /** The addressed resource does not exist (404). */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** The request is valid but conflicts with current state (409). */
    CONFLICT(HttpStatus.CONFLICT),

    /** The caller's credentials do not identify them well enough for this request (401). */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /** The caller is identified but may not do this (403). */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** A staff link code is wrong, expired or already used; deliberately one answer for all three (400). */
    INVALID_CODE(HttpStatus.BAD_REQUEST),

    /** A dependency the request needs, such as the shared cache, is down; the caller may retry (503). */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    /** A staff display name breaks a rule of its own (422). */
    NAME_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY),

    /** The staff notice was not acknowledged before the display name was saved (422). */
    NOTICE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY),

    /** Another staff member already uses this display name, ignoring case and normalisation (409). */
    NAME_TAKEN(HttpStatus.CONFLICT),

    /** A staff account without a display name may not write anything a player can see (409). */
    STAFF_PROFILE_REQUIRED(HttpStatus.CONFLICT),

    /** A kick's scope and target do not fit: a scoped kick without a target, a target that is no server or section (422). */
    KICK_TARGET_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),

    /** A warning was asked for, but the template's ladder reached a step that is not a warning (422). */
    WARN_STEP_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY);

    private final HttpStatus status;

    ErrorKind(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return this.status;
    }
}
