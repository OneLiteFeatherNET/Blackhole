package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.serde.annotation.Serdeable;

/** What a kick record is. */
@Serdeable
public enum KickKind {

    /** One player, kept as a {@code KICK} punishment row in that player's history. */
    PLAYER,

    /** Everybody on a server or section: one audited action, no per-player record. */
    MASS
}
