package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.annotation.Serdeable;

/** How soon staff should look at a case. */
@Serdeable
public enum CasePriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
