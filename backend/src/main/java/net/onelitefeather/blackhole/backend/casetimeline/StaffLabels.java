package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

/**
 * How a staff member is named in the appeal chat's records: the one rule that the stored protocol and the player's copy both
 * use. Each copy keeps its own fallback for a staff member without a name, so the outputs stay as they were.
 */
public final class StaffLabels {

    private StaffLabels() {
    }

    /** The name when there is one, otherwise the copy's fallback for a staff member without a name. */
    public static String named(@Nullable String name, String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }
}
