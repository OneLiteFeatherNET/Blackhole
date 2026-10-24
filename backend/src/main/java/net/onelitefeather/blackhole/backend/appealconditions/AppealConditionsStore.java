package net.onelitefeather.blackhole.backend.appealconditions;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The versions of the appeal conditions. Append-only: a version is never changed or removed, a save adds the next number.
 */
public interface AppealConditionsStore {

    /**
     * One saved version.
     *
     * @param recordedByOid the verified staff member who saved it; {@code null} for the seeded default
     */
    record Version(int version, String text, long createdAt, @Nullable UUID recordedByOid) {
    }

    /** The highest version, empty only if the table was emptied by hand. */
    Optional<Version> current();

    /** Every version, newest first. */
    List<Version> list();

    /**
     * Appends {@code highest + 1}.
     *
     * @return the new version, or empty when a concurrent save took the number first
     */
    Optional<Version> append(String text, @Nullable UUID recordedByOid, long now);
}
