package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.serde.annotation.Serdeable;

/** Why a share was withdrawn, as a code; the free text of a reason is never stored. */
@Serdeable
public enum ShareRevokeReason {
    MISTAKE,
    PRIVACY,
    OTHER
}
