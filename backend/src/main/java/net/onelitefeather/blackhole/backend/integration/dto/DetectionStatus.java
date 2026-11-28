package net.onelitefeather.blackhole.backend.integration.dto;

import io.micronaut.serde.annotation.Serdeable;

/** What happened to one detection of a batch. */
@Serdeable
public enum DetectionStatus {
    /** Stored now. */
    ACCEPTED,
    /** A detection with the same id was stored before; nothing changed. */
    DUPLICATE,
    /** Not stored; the item's {@code reason} says why. */
    REJECTED
}
