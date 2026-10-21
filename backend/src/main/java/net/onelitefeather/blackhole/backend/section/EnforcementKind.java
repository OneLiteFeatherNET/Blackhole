package net.onelitefeather.blackhole.backend.section;

import io.micronaut.serde.annotation.Serdeable;

/**
 * How a section's punishments are enforced. The kinds are an enum, not data, because the proxy has
 * to implement each of them: a new section of an existing kind needs no code change, a new kind does.
 */
@Serdeable
public enum EnforcementKind {

    /** The player cannot chat. */
    MUTE,

    /** The player cannot join the network. */
    BAN,

    /** The player cannot connect to backend servers matching the section's patterns. */
    SERVER
}
