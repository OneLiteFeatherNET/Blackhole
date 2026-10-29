package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.intel.IntelClass;

/** The refusal rules of each level. Pure: no state, no I/O. */
public final class GuardLevelRules {

    private GuardLevelRules() {
    }

    /**
     * Whether a level refuses a login from an address of this class. STRICT and LOCKDOWN refuse every class.
     *
     * @param classification the class the address matched, or null for a clean address
     */
    public static boolean refusesClassification(GuardLevel level, IntelClass classification) {
        return classification != null && level != GuardLevel.OFF;
    }

    /** Whether a level refuses an owner that is not known to the network. Only LOCKDOWN does. */
    public static boolean refusesUnknownOwner(GuardLevel level, boolean known) {
        return level == GuardLevel.LOCKDOWN && !known;
    }
}
