package net.onelitefeather.blackhole.analytics.mapping;

public enum DeadLetterReason {
    /** The event type belongs to no family. */
    UNKNOWN_TYPE,
    /** The payload version is not one the sink knows. */
    UNSUPPORTED_VERSION,
    /** A typed column got a value of the wrong kind, or the owner is not a string. */
    MALFORMED,
    /** A raw player UUID or an IP address is in a pseudonymous payload. */
    PSEUDONYMITY_VIOLATION
}
