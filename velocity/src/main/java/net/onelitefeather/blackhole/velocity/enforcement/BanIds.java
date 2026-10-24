package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Ban ID a punished player quotes in the appeal portal. It is a credential: it is held in memory
 * only, shown on the player's own screen and never logged, persisted or put in the shared cache.
 */
@FunctionalInterface
public interface BanIds {

    /** Knows no Ban IDs, so screens carry none. */
    BanIds NONE = punishmentIdentifier -> Optional.empty();

    /**
     * @param punishmentIdentifier the backend identifier of the punishment, null if unknown
     * @return the Ban ID, empty if it is unknown or could not be fetched in time
     */
    Optional<String> of(@Nullable String punishmentIdentifier);
}
