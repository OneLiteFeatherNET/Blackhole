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
    INVALID_CODE(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorKind(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return this.status;
    }
}
