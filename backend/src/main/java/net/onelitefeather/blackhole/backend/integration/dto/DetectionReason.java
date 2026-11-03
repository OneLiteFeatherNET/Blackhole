package net.onelitefeather.blackhole.backend.integration.dto;

import io.micronaut.serde.annotation.Serdeable;

/** Why one detection of a batch was rejected. The names are the wire codes; they never change meaning. */
@Serdeable
public enum DetectionReason {
    /** The envelope or the payload breaks its rules: a missing or unknown field, a length, a type, a schema rule. */
    SCHEMA_INVALID,
    /** No schema is registered for this source system, kind and schema version, or the registration does not allow it. */
    UNKNOWN_KIND,
    /** A string of the payload (or the summary) contains a player UUID or an IP address. */
    PII_IN_PAYLOAD,
    /** occurredAt is more than 24 hours in the past or more than 5 minutes in the future. */
    OCCURRED_AT_OUT_OF_RANGE,
    /** The sourceSystem in the body is not the one of the caller's registration. */
    SOURCE_MISMATCH,
    /** The server label is outside the registration's tasks and static servers. */
    SERVER_OUT_OF_SCOPE
}
