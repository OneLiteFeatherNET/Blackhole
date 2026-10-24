package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.annotation.Serdeable;

/** Why a Ban ID was reissued: a code. {@code KEY_ROTATION} is the system reissue after {@code BLACKHOLE_BANID_KEY} changed. */
@Serdeable
public enum BanIdReissueReason {
    NOT_PLAYERS_FAULT,
    OTHER,
    KEY_ROTATION
}
