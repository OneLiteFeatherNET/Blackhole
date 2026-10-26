package net.onelitefeather.blackhole.backend.casetimeline.dto;

import io.micronaut.http.HttpStatus;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Why a case request failed in an expected way, so the controller maps it 1:1 to a status. The case
 * API needs 403 and 422 next to the shared {@code ErrorKind} statuses, and keeping them here leaves
 * every other resource's error schema untouched.
 */
@Serdeable
public enum CaseErrorKind {

    /** The request is malformed or breaks a rule of its own (400). */
    VALIDATION(HttpStatus.BAD_REQUEST),

    /** The caller may not do this to this note (403). */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** The punishment, case or note does not exist (404). */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** The case changed since the caller read it, a short id is ambiguous, or the note is redacted (409). */
    CONFLICT(HttpStatus.CONFLICT),

    /** The request is understood but names something unknown, such as an assignee who is no staff member (422). */
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY),

    /** The staff account has no display name yet, so nothing it writes may reach a player (409). */
    STAFF_PROFILE_REQUIRED(HttpStatus.CONFLICT),

    /** Something the request needs cannot be produced right now, such as a free Ban ID candidate (503). */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    CaseErrorKind(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return this.status;
    }
}
