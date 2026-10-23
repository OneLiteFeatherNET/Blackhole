package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.serde.annotation.Serdeable;

/** What the list of active punishments is ordered by. */
@Serdeable
public enum ActivePunishmentSort {

    /** When the punishment was applied. */
    CREATED,

    /** When it ends; a permanent punishment never ends, so it sorts as the latest. */
    EXPIRES
}
