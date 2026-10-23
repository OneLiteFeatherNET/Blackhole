package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.serde.annotation.Serdeable;

/** The direction of a sorted list. */
@Serdeable
public enum SortDirection {
    ASC,
    DESC
}
