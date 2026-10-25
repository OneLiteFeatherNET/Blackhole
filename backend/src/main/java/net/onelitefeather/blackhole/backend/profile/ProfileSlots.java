package net.onelitefeather.blackhole.backend.profile;

import java.util.Map;
import java.util.Optional;

/**
 * Row-level writes of a player's punishment state: the active slot per (owner, section), the history
 * and the session telemetry. Each method is one atomic step on exactly the rows it names, so a
 * concurrent writer touching another section - or the telemetry - is never overwritten the way a
 * write-back of the whole {@link PunishmentProfileEntity} would.
 */
public interface ProfileSlots {

    /**
     * Outcome of {@link #occupy}.
     *
     * @param occupied       whether the punishment now holds the slot
     * @param profileCreated whether the owner had no profile before this call
     * @param replaced       the identifier of the punishment that held the slot and moved to history
     */
    record Placement(boolean occupied, boolean profileCreated, Optional<String> replaced) {
    }

    /**
     * Makes {@code punishmentIdentifier} the active punishment of the section, moving a temporary one
     * it replaces into the history, and creates the profile if needed. A permanent punishment holding
     * the slot is never replaced: nothing changes and {@code occupied} is {@code false}.
     */
    Placement occupy(String owner, String sectionKey, String punishmentIdentifier);

    /**
     * Moves the section's active punishment into the history, but only if the slot still holds
     * {@code punishmentIdentifier}.
     *
     * @return {@code false} if the slot is empty or holds another punishment (nothing changed)
     */
    boolean clearIf(String owner, String sectionKey, String punishmentIdentifier);

    /**
     * Adds a punishment that never occupies a slot, such as a kick, to the owner's history, creating the
     * profile if needed. Adding it twice keeps one entry.
     */
    void appendHistory(String owner, String punishmentIdentifier);

    /**
     * Merges the given telemetry fields into {@code metaData.sessionInfo}, creating the profile if
     * needed. Touches nothing else.
     */
    void mergeSessionInfo(String owner, Map<String, Object> fields);
}
