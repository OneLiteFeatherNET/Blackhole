package net.onelitefeather.blackhole.backend.publicapi.dto;

import io.micronaut.http.HttpStatus;
import io.micronaut.serde.annotation.Serdeable;

/** Why a public request failed in an expected way, so the controller maps it 1:1 to a status. */
@Serdeable
public enum PublicErrorKind {

    /** The session is missing, expired, or the Ban ID is not accepted (401). */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /** The session's scope does not allow this (403). */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** The text is empty, too long or malformed (400). */
    VALIDATION(HttpStatus.BAD_REQUEST),

    /** The text contains a UUID, an IP address or a Ban ID (422). */
    PII_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),

    /** The request conflicts with the state of the appeal (409). */
    CONFLICT(HttpStatus.CONFLICT),

    /** The punishment is gone (404). */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** A dependency the request needs cannot answer (503). */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    PublicErrorKind(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return this.status;
    }
}
