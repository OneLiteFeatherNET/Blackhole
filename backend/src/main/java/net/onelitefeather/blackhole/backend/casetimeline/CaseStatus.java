package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.annotation.Serdeable;

/** The staff workflow state of a case; not the state of the punishment. */
@Serdeable
public enum CaseStatus {
    OPEN,
    IN_REVIEW,
    WAITING,
    CLOSED
}
