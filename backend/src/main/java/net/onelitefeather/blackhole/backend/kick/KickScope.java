package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.serde.annotation.Serdeable;

/** Where a kick takes the player from. */
@Serdeable
public enum KickScope {

    /** Off the whole network. The default when a request names no scope. */
    NETWORK,

    /** Off one backend server. */
    SERVER,

    /** Off every server a section covers. */
    SECTION
}
