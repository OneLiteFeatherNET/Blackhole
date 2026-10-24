package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.annotation.Serdeable;

/** Why a Ban ID is marked leaked: a code, free text belongs in a staff note. */
@Serdeable
public enum BanIdLeakReason {
    POSTED_PUBLIC,
    SHARED,
    FOUND,
    OTHER
}
