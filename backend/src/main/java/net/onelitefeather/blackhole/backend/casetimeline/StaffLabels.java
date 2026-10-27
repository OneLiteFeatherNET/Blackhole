package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

/**
 * How a staff member is named in the appeal chat's records: the one rule that the stored protocol and the player's copy both
 * use. Each copy keeps its own fallback for a staff member without a name, so the outputs stay as they were.
 */
public final class StaffLabels {

    /**
     * The header sentence of every new protocol, both copies (design 4.7). The wording is pending legal confirmation: change
     * it here only.
     */
    public static final String NOTICE = "Staff members appear by the display name they chose. Their names are recorded in this chat.";

    private StaffLabels() {
    }

    /** The name when there is one, otherwise the copy's fallback for a staff member without a name. */
    public static String named(@Nullable String name, String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }
}
