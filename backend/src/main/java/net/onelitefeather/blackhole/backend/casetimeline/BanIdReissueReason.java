package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.annotation.Serdeable;

/** Why an admin reissued a Ban ID: a code. */
@Serdeable
public enum BanIdReissueReason {
    NOT_PLAYERS_FAULT,
    OTHER
}
