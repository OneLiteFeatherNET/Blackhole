package net.onelitefeather.blackhole.backend.discord;

import io.micronaut.serde.annotation.Serdeable;

/** Whose Discord account a link belongs to. */
@Serdeable
public enum DiscordLinkKind {

    /** A staff member, identified by their Entra object id. */
    STAFF,

    /** A player, identified by the hashed owner (never the raw Minecraft UUID). */
    PLAYER
}
