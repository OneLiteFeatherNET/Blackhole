package net.onelitefeather.blackhole.backend.evidence.dto;

import io.micronaut.http.HttpStatus;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Why an evidence request failed in an expected way, so the controller maps it 1:1 to a status.
 * Evidence needs more statuses than the shared {@code ErrorKind} offers (413, 415, 422, 503), and
 * keeping them here leaves every other resource's error schema untouched.
 */
@Serdeable
public enum EvidenceErrorKind {

    /** The request is malformed or breaks a rule of its own (400). */
    VALIDATION(HttpStatus.BAD_REQUEST),

    /** The punishment, evidence item or file does not exist (404). */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** The request conflicts with current state, for example an ambiguous short id (409). */
    CONFLICT(HttpStatus.CONFLICT),

    /** The payload or the file is larger than the configured limit (413). */
    PAYLOAD_TOO_LARGE(HttpStatus.REQUEST_ENTITY_TOO_LARGE),

    /** The detected file type is not on the allow-list (415). */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    /** The content is understood but must not be stored: personal data in a payload, malware (422). */
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY),

    /** The chat upload was flagged by the virus scanner (422); the signature name is never shown. */
    FILE_FLAGGED(HttpStatus.UNPROCESSABLE_ENTITY),

    /** The chat upload is a PDF that names active content or encryption (422). */
    FILE_ACTIVE_CONTENT(HttpStatus.UNPROCESSABLE_ENTITY),

    /** The appeal or case already holds as many chat files as the quota allows, or as many are staged (409). */
    QUOTA(HttpStatus.CONFLICT),

    /** A dependency evidence needs (the virus scanner, the file store) is not available (503). */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    EvidenceErrorKind(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return this.status;
    }
}
