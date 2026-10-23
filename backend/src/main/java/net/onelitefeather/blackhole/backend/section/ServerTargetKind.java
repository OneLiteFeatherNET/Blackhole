package net.onelitefeather.blackhole.backend.section;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What the entries of a SERVER section's {@code servers} list mean. Stored as a string; the allowed
 * values are enforced here, not by the database.
 */
@Serdeable
public enum ServerTargetKind {

    /** Glob patterns over the Velocity server name (the default, and what every older section is). */
    PATTERN,

    /** Exact names of CloudNet tasks. */
    CLOUDNET_TASK,

    /** Exact names of CloudNet groups. */
    CLOUDNET_GROUP,

    /** Exact names of CloudNet services (a service name can belong to another instance after a restart). */
    CLOUDNET_SERVICE
}
