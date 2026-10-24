package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.serde.annotation.Serdeable;

/** What a player session may do. */
@Serdeable
public enum PlayerScope {

    /** Everything the portal offers. */
    FULL,

    /** A leaked Ban ID: the facts and the notice only; no filing, no messages, no conversation, no decision. */
    LIMITED
}
