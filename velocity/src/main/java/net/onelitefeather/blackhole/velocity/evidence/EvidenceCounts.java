package net.onelitefeather.blackhole.velocity.evidence;

import org.jetbrains.annotations.Nullable;

import java.util.OptionalLong;

/**
 * How many valid evidence items a punishment has: the only thing about evidence a punished player
 * is ever told. It exposes the number only, never any evidence content.
 */
@FunctionalInterface
public interface EvidenceCounts {

    /**
     * @param punishmentIdentifier the identifier of the punishment, null if unknown
     * @return the count, empty if it is unknown or could not be fetched in time
     */
    OptionalLong of(@Nullable String punishmentIdentifier);
}
